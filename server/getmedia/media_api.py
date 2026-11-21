"""媒体库 / 提取 / 媒体流的 HTTP 层（P2a）。

约定：
- 全部 owner 级；免费版没有这些能力，边缘也不放行。
- ``GET /v2/media/{id}`` 优先发本地文件（支持 Range），没有文件才当场解析并中转，
  中转不落盘、客户端断开即停。
- 提取任务在响应返回后由 BackgroundTasks 执行；任务状态落 ``extract_jobs``。
"""
from __future__ import annotations

import logging
import re
from pathlib import Path
from typing import Literal
from urllib.parse import quote
from uuid import uuid4

from fastapi import BackgroundTasks, Depends, Header, Query, Response
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field

from .errors import ApiError
from .extractor import MediaResolver, ResolveError, iter_source
from .media import MediaError, MediaStore
from .service import AuthCtx

log = logging.getLogger("getmedia.media_api")

_RANGE = re.compile(r"^bytes=(\d*)-(\d*)$")


class ResolveBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    text: str = Field(min_length=1, max_length=4000)
    media_type: Literal["audio", "video"] = "video"


class ResolveOut(BaseModel):
    platform: str
    video_id: str
    title: str
    duration_sec: float | None = None
    uploader: str | None = None
    cover_url: str | None = None
    source_url: str | None = None
    estimated_bytes: int = 0


class ExtractTarget(BaseModel):
    model_config = ConfigDict(extra="forbid")
    track_id: int = Field(gt=0)
    platform: Literal["bilibili", "douyin"]
    video_id: str = Field(min_length=1, max_length=128)
    page: int = Field(default=1, ge=1, le=9999)


class ExtractBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    input: str = Field(min_length=1, max_length=4000, description="作品链接或分享文案")
    media_type: Literal["audio", "video"] = "video"
    storage: Literal["metadata", "archive", "cache"] = "metadata"
    target: ExtractTarget | None = None


def _media_key(platform: str, video_id: str, page: int) -> tuple[str, str, int]:
    if platform == "bilibili":
        legacy = re.fullmatch(r"(BV[0-9A-Za-z]{10})-p([1-9][0-9]{0,3})", video_id)
        if legacy:
            encoded_page = int(legacy[2])
            if page not in (1, encoded_page):
                raise MediaError("target_mismatch", "条目的分集标识冲突", 409)
            return platform, legacy[1], encoded_page
    return platform, video_id, page


class TrackPatchBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    title: str | None = Field(None, min_length=1, max_length=200)
    uploader: str | None = Field(None, max_length=100)
    pinned: bool | None = None


class SyncTitleBody(BaseModel):
    """手机同步专用改名：只收标题（P0-2 阶段 5）。

    多一个字段就 422：手机写通道的能力边界要窄——``uploader`` / ``pinned`` 仍走网页的
    原 PATCH，避免手机端一次改名顺手改掉别的东西。
    """
    model_config = ConfigDict(extra="forbid")
    title: str = Field(max_length=500)


def _if_match_seq(raw: str | None) -> int:
    """解析同步写请求的 ``If-Match``。缺失 428、格式不对 400（不静默降级成无条件写）。"""
    if raw is None or not raw.strip():
        raise ApiError(428, "precondition_required", "缺少条目版本（If-Match）")
    text = raw.strip()
    if text.startswith("W/"):
        raise ApiError(400, "bad_version", "条目版本必须使用强校验")
    if len(text) >= 2 and text.startswith('"') and text.endswith('"'):
        text = text[1:-1]
    if not text.isdigit() or int(text) <= 0:
        raise ApiError(400, "bad_version", "条目版本不正确")
    return int(text)


class TrackCreateBody(BaseModel):
    """手机端"这条要进曲库"（P0-2 阶段 1）。

    刻意不收 ``cover_url``：隐私 v3 明确"不同步封面地址"。字段上限只做粗挡，
    空值与长度规整在 ``MediaStore.create_metadata`` 里判 400，返回稳定错误码。
    """
    model_config = ConfigDict(extra="forbid")
    platform: str = Field(max_length=32)
    video_id: str = Field(max_length=128)
    page: int = 1                           # 越界在 store 里判 400（保持稳定错误码，不用 422）
    media_type: str = Field(max_length=16)
    title: str = Field(max_length=500)
    uploader: str | None = Field(None, max_length=100)
    duration_sec: float | None = Field(None, ge=0)
    source_url: str | None = Field(None, max_length=2048)


def _translate(exc: Exception) -> None:
    if isinstance(exc, MediaError):
        raise ApiError(exc.status, exc.code, exc.detail, headers=exc.headers, extra=exc.extra) from exc
    if isinstance(exc, ResolveError):
        raise ApiError(exc.status, exc.code, exc.detail) from exc
    raise exc


def _parse_range(header: str | None, size: int) -> tuple[int, int] | None:
    """只支持单段 Range；不合法返回 None（调用方按 416 处理），没有 Range 也返回 None。"""
    if not header:
        return None
    m = _RANGE.match(header.strip())
    if not m:
        return None
    a, b = m.group(1), m.group(2)
    if a == "" and b == "":
        return None
    if a == "":
        n = int(b)
        if n <= 0:
            return None
        start, end = max(0, size - n), size - 1
    else:
        start = int(a)
        end = int(b) if b else size - 1
    if start >= size or start > end:
        return None
    return start, min(end, size - 1)


_UNSAFE_NAME = re.compile(r'[\x00-\x1f\x7f/\\:*?"<>|]+')


def attachment_name(title: str | None, track_id: int, ext: str) -> str:
    """把标题变成可安全放进响应头的文件名。

    标题是用户/平台给的内容，可能带换行、引号、斜杠：不清洗会直接构成响应头注入，
    也会让浏览器存出奇怪的路径。这里只保留可打印字符，其余换成下划线。
    """
    cleaned = _UNSAFE_NAME.sub("_", (title or "").strip())
    cleaned = re.sub(r"\s+", " ", cleaned).strip(" ._")
    if not cleaned:
        cleaned = f"media-{track_id}"
    return f"{cleaned[:60]}.{ext.lstrip('.') or 'mp4'}"


def attachment_disposition(name: str, track_id: int, ext: str) -> str:
    """RFC 6266：ASCII 兜底名 + UTF-8 真名（中文标题不会变成乱码）。"""
    fallback = f"media-{track_id}.{ext.lstrip('.') or 'mp4'}"
    return f"attachment; filename=\"{fallback}\"; filename*=UTF-8''{quote(name, safe='')}"


_MIME_BY_EXT = {
    ".mp4": "video/mp4", ".mkv": "video/x-matroska", ".webm": "video/webm",
    ".m4a": "audio/mp4", ".mp3": "audio/mpeg", ".flac": "audio/flac", ".wav": "audio/wav",
}


def _file_response(path: Path, range_header: str | None, media_type: str,
                   disposition: str | None = None) -> Response:
    size = path.stat().st_size
    # 转换过的文件（mp3/flac/wav…）不能一律报 video/mp4：浏览器存下来的类型会错
    ctype = _MIME_BY_EXT.get(path.suffix.lower()) or (
        "video/mp4" if media_type == "video" else "audio/mp4")
    extra = {"Content-Disposition": disposition} if disposition else {}
    rng = _parse_range(range_header, size)
    if range_header and rng is None:
        return Response(status_code=416, headers={"Content-Range": f"bytes */{size}", "Accept-Ranges": "bytes"})
    if rng is None:
        return StreamingResponse(_read_file(path, 0, size - 1), media_type=ctype,
                                 headers={"Content-Length": str(size), "Accept-Ranges": "bytes", **extra})
    start, end = rng
    return StreamingResponse(_read_file(path, start, end), status_code=206, media_type=ctype,
                             headers={"Content-Range": f"bytes {start}-{end}/{size}",
                                      "Content-Length": str(end - start + 1), "Accept-Ranges": "bytes",
                                      **extra})


def _read_file(path: Path, start: int, end: int, chunk: int = 256 * 1024):
    with open(path, "rb") as f:
        f.seek(start)
        left = end - start + 1
        while left > 0:
            data = f.read(min(chunk, left))
            if not data:
                break
            left -= len(data)
            yield data


def register(app, guard, engine, clock, *, resolver=None, store: MediaStore | None = None) -> MediaStore:
    """挂载 P2a 路由；返回 MediaStore 供后台任务与其他模块复用。"""
    store = store or MediaStore(engine, app.state.settings, clock)
    resolver = resolver or MediaResolver()
    app.state.media, app.state.extractor = store, resolver

    def _tid(raw: str) -> int:
        try:
            return int(raw)
        except (TypeError, ValueError):
            raise ApiError(404, "track_not_found", "媒体不存在") from None

    # ---------- 解析 / 提取 ----------
    @app.post("/v2/resolve", response_model=ResolveOut)
    def resolve(body: ResolveBody, _ctx: AuthCtx = Depends(guard)):
        try:
            m = resolver.resolve(body.text, media_type=body.media_type)
        except Exception as exc:  # noqa: BLE001
            _translate(exc)
        return ResolveOut(platform=m.platform, video_id=m.video_id, title=m.title,
                          duration_sec=m.duration_sec, uploader=m.uploader, cover_url=m.cover_url,
                          source_url=m.source_url, estimated_bytes=m.estimated_bytes)

    def _run_target_job(job_id: int, user_id: str, body: ExtractBody, target: ExtractTarget) -> None:
        track = store.get(user_id, target.track_id)
        expected = _media_key(target.platform, target.video_id, target.page)
        if (track["media_type"] != body.media_type
                or _media_key(track["platform"], track["video_id"], track["page"]) != expected):
            raise MediaError("target_mismatch", "原条目的作品、分集或媒体类型与请求不符", 409)
        if store.file_for(user_id, target.track_id) is not None:
            store.job_update(job_id, status="done", platform=track["platform"], video_id=track["video_id"],
                             track_id=track["id"], finished_at=store.now())
            return
        if track["storage"] == "archive":
            raise MediaError("stored_file_missing", "服务器归档文件缺失", 409)
        if track["storage"] not in ("metadata", "cache") or body.storage != "cache":
            raise MediaError("target_storage_invalid", "补取原条目只支持缓存模式", 409)
        resolved = resolver.resolve_target(body.input, media_type=body.media_type, page=expected[2])
        if (resolved.media_type != body.media_type
                or _media_key(resolved.platform, resolved.video_id, resolved.page) != expected):
            raise MediaError("target_mismatch", "返回的作品、分集或媒体类型与请求不符", 409)

        store.ensure_room("cache", resolved.estimated_bytes)
        filename = f"{track['id']}-{uuid4().hex}.{resolved.ext}"
        last_error = "no_source"
        for source in resolved.sources:
            try:
                path, nbytes, sha = store.write_atomic(store.dir_for("cache"), filename, iter_source(resolver, source))
            except (ResolveError, OSError) as exc:
                last_error = exc.detail if isinstance(exc, ResolveError) else type(exc).__name__
                continue
            try:
                if nbytes <= 0:
                    raise MediaError("download_failed", "上游文件为空", 502)
                store.attach_file(user_id, track["id"], storage="cache", filename=filename, nbytes=nbytes,
                                  sha256=sha, expected_seq=track["updated_seq"])
            except Exception:
                path.unlink(missing_ok=True)
                raise
            store.job_update(job_id, status="done", platform=track["platform"], video_id=track["video_id"],
                             track_id=track["id"], finished_at=store.now())
            return
        raise MediaError("download_failed", f"下载失败：{last_error}"[:200], 502)

    def _run_job(job_id: int, user_id: str, body: ExtractBody) -> None:
        input_, media_type, storage = body.input, body.media_type, body.storage
        store.job_update(job_id, status="running", started_at=store.now())
        try:
            if body.target is not None:
                _run_target_job(job_id, user_id, body, body.target)
                return
            m = resolver.resolve(input_, media_type=media_type)
        except (ResolveError, MediaError) as exc:
            store.job_update(job_id, status="failed", error_code=exc.code, error_detail=exc.detail,
                             finished_at=store.now())
            return
        except Exception as exc:  # noqa: BLE001
            log.warning("提取任务 %s 解析异常：%s", job_id, type(exc).__name__)
            store.job_update(job_id, status="failed", error_code="resolve_error",
                             error_detail="解析失败，请稍后重试", finished_at=store.now())
            return

        if storage == "metadata":
            track = store.upsert(user_id, media_type=media_type, platform=m.platform, video_id=m.video_id,
                                 page=m.page, title=m.title, uploader=m.uploader,
                                 duration_sec=m.duration_sec, cover_url=m.cover_url, source_url=m.source_url,
                                 storage="metadata")
            store.job_update(job_id, status="done", platform=m.platform, video_id=m.video_id,
                             track_id=track["id"], finished_at=store.now())
            return

        try:
            store.ensure_room(storage, m.estimated_bytes)
        except MediaError as exc:
            store.job_update(job_id, status="failed", error_code=exc.code, error_detail=exc.detail,
                             finished_at=store.now())
            return

        track = store.upsert(user_id, media_type=media_type, platform=m.platform, video_id=m.video_id,
                             page=m.page, title=m.title, uploader=m.uploader, duration_sec=m.duration_sec,
                             cover_url=m.cover_url, source_url=m.source_url, storage="metadata")
        filename = f"{track['id']}-{m.video_id}.{m.ext}"
        last_error = "no_source"
        for source in m.sources:
            try:
                _path, nbytes, sha = store.write_atomic(
                    store.dir_for(storage), filename, iter_source(resolver, source))
            except ResolveError as exc:
                last_error = exc.detail
                continue
            except Exception as exc:  # noqa: BLE001
                last_error = type(exc).__name__
                continue
            store.attach_file(user_id, track["id"], storage=storage, filename=filename,
                              nbytes=nbytes, sha256=sha)
            store.job_update(job_id, status="done", platform=m.platform, video_id=m.video_id,
                             track_id=track["id"], finished_at=store.now())
            return

        store.job_update(job_id, status="failed", error_code="download_failed",
                         error_detail=f"下载失败：{last_error}"[:200], track_id=track["id"],
                         finished_at=store.now())

    @app.post("/v2/extract", status_code=202)
    def extract(body: ExtractBody, background: BackgroundTasks, ctx: AuthCtx = Depends(guard)):
        job_id = store.job_create(ctx.user_id, media_type=body.media_type, storage=body.storage,
                                  input_=body.input)
        background.add_task(_run_job, job_id, ctx.user_id, body)
        return {"id": job_id, "status": "queued"}

    @app.get("/v2/extraction-jobs")
    def extraction_jobs(limit: int = Query(50, ge=1, le=200), ctx: AuthCtx = Depends(guard)):
        return {"jobs": store.jobs_list(ctx.user_id, limit)}

    @app.get("/v2/extraction-jobs/{job_id}")
    def extraction_job(job_id: str, ctx: AuthCtx = Depends(guard)):
        try:
            return store.job_get(ctx.user_id, _tid(job_id))
        except MediaError as exc:
            _translate(exc)

    # ---------- 媒体库 ----------
    @app.get("/v2/tracks")
    def list_tracks(media_type: Literal["audio", "video"] | None = None,
                    since_seq: int = Query(0, ge=0), limit: int = Query(200, ge=1, le=500),
                    include_deleted: bool = False, ctx: AuthCtx = Depends(guard)):
        items = store.list(ctx.user_id, media_type=media_type, since_seq=since_seq,
                           limit=limit, include_deleted=include_deleted)
        return {"items": items, "count": len(items)}

    @app.get("/v2/sync")
    def sync(since_seq: int = Query(0, ge=0), limit: int = Query(200, ge=1, le=500),
             ctx: AuthCtx = Depends(guard)):
        items = store.list(ctx.user_id, since_seq=since_seq, limit=limit, include_deleted=True)
        next_seq = max((i["updated_seq"] for i in items), default=since_seq)
        return {"items": items, "next_seq": next_seq, "has_more": len(items) >= limit,
                "server_time": store.now()}

    @app.post("/v2/tracks")
    def create_track(body: TrackCreateBody, ctx: AuthCtx = Depends(guard)):
        """手机端上传"本机新增的条目"（只写元数据，不落盘）。

        - 同键已存在（且未删除）→ 200 ``created=false``，**既有行一个字段都不改**；
        - 同键是墓碑 → 409 ``track_deleted``，响应体里带 ``track``，由客户端弹确认；
        - 超过每账号行数上限 → 409 ``library_full``。
        """
        try:
            track, created = store.create_metadata(
                ctx.user_id, media_type=body.media_type, platform=body.platform,
                video_id=body.video_id, page=body.page, title=body.title,
                uploader=body.uploader, duration_sec=body.duration_sec,
                source_url=body.source_url,
            )
        except MediaError as exc:
            _translate(exc)
        return {"track": track, "created": created}

    @app.get("/v2/tracks/{track_id}")
    def get_track(track_id: str, ctx: AuthCtx = Depends(guard)):
        try:
            return store.get(ctx.user_id, _tid(track_id), include_deleted=True, include_file_hash=True)
        except MediaError as exc:
            _translate(exc)

    @app.patch("/v2/tracks/{track_id}")
    def patch_track(track_id: str, body: TrackPatchBody, ctx: AuthCtx = Depends(guard)):
        try:
            return store.patch(ctx.user_id, _tid(track_id), title=body.title,
                               uploader=body.uploader, pinned=body.pinned)
        except MediaError as exc:
            _translate(exc)

    @app.delete("/v2/tracks/{track_id}")
    def delete_track(track_id: str, ctx: AuthCtx = Depends(guard)):
        try:
            t = store.delete(ctx.user_id, _tid(track_id))
        except MediaError as exc:
            _translate(exc)
        return {"ok": True, "id": t["id"], "deleted": t["deleted"], "updated_seq": t["updated_seq"]}

    # ---------- 手机同步专用写路径（P0-2 阶段 5） ----------
    #
    # 单独两条路径，不改旧 PATCH/DELETE 的行为：网页端仍走上面的无条件接口，
    # 手机走带 If-Match 的条件写。边缘只按前缀转发，所以这里不需要改 nginx。
    @app.delete("/v2/sync/tracks/{track_id}")
    def delete_track_for_sync(track_id: str, response: Response,
                              if_match: str | None = Header(default=None, alias="If-Match"),
                              ctx: AuthCtx = Depends(guard)):
        expected = _if_match_seq(if_match)
        try:
            t = store.delete_for_sync(ctx.user_id, _tid(track_id), expected)
        except MediaError as exc:
            _translate(exc)
        response.headers["ETag"] = f'"{t["updated_seq"]}"'
        return {"ok": True, "id": t["id"], "deleted": t["deleted"], "updated_seq": t["updated_seq"]}

    @app.patch("/v2/sync/tracks/{track_id}")
    def patch_track_for_sync(track_id: str, body: SyncTitleBody, response: Response,
                             if_match: str | None = Header(default=None, alias="If-Match"),
                             ctx: AuthCtx = Depends(guard)):
        expected = _if_match_seq(if_match)
        try:
            t = store.patch_title_for_sync(ctx.user_id, _tid(track_id), body.title, expected)
        except MediaError as exc:
            _translate(exc)
        response.headers["ETag"] = f'"{t["updated_seq"]}"'
        return t

    @app.post("/v2/tracks/{track_id}/restore")
    def restore_track(track_id: str, ctx: AuthCtx = Depends(guard)):
        try:
            return store.restore(ctx.user_id, _tid(track_id))
        except MediaError as exc:
            _translate(exc)

    @app.delete("/v2/tracks/{track_id}/file")
    def drop_file(track_id: str, ctx: AuthCtx = Depends(guard)):
        try:
            return store.remove_file(ctx.user_id, _tid(track_id))
        except MediaError as exc:
            _translate(exc)

    # ---------- 媒体流 ----------
    @app.get("/v2/media/{track_id}")
    def media(track_id: str, range_header: str | None = Header(default=None, alias="Range"),
              download: bool = Query(default=False),
              stored_only: bool = Query(default=False),
              ctx: AuthCtx = Depends(guard)):
        """``?download=1`` 时按附件下发（浏览器直接存盘），否则内联给播放器。

        两种来源都支持：服务器有文件就直读，没有就当场解析并中转——所以「仅元数据」的
        条目也能下载，代价是每次都要现解析、且拿不到总长度。
        """
        tid = _tid(track_id)
        try:
            track = store.get(ctx.user_id, tid, include_deleted=not stored_only)
            tf = store.file_for(ctx.user_id, tid)
        except MediaError as exc:
            _translate(exc)
        if tf is not None:
            store.touch(tid)
            disposition = None
            if download:
                ext = tf.path.suffix
                disposition = attachment_disposition(attachment_name(track["title"], tid, ext), tid, ext)
            try:
                response = _file_response(tf.path, range_header, track["media_type"], disposition)
            except FileNotFoundError:
                if not stored_only:
                    raise
            else:
                if stored_only:
                    response.headers["X-GetMedia-Media-Source"] = "stored"
                return response

        if stored_only:
            raise ApiError(409, "stored_file_missing", "服务器没有可读取的已存文件")

        # 没有落盘文件：当场解析并中转，不写盘
        if not track.get("source_url"):
            raise ApiError(409, "media_unavailable", "该条目没有可用的媒地址，请在客户端重新解析")
        try:
            m = resolver.resolve(track["source_url"], media_type=track["media_type"])
        except Exception as exc:  # noqa: BLE001
            _translate(exc)
        if not m.sources:
            raise ApiError(409, "media_unavailable", "平台没有返回可播放地址")
        resp = resolver.open_stream(m.sources[0], range_header=range_header)
        status = resp.status_code if resp.status_code in (200, 206) else 502
        headers = {"Accept-Ranges": "bytes"}
        for k in ("Content-Range", "Content-Length", "Content-Type"):
            if k in resp.headers:
                headers[k] = resp.headers[k]
        headers.setdefault("Content-Type", "video/mp4" if track["media_type"] == "video" else "audio/mp4")
        if download:
            headers["Content-Disposition"] = attachment_disposition(
                attachment_name(track["title"], tid, m.ext), tid, m.ext)

        def _body():
            try:
                yield from resp.iter_bytes()
            finally:
                resp.close()

        if status >= 400:
            resp.close()
            raise ApiError(502, "media_upstream_failed", "上游拒绝取流")
        store.touch(tid)
        return StreamingResponse(_body(), status_code=status, headers=headers)

    # ---------- 维护 ----------
    @app.get("/v2/maintenance/storage")
    def storage_report(_ctx: AuthCtx = Depends(guard)):
        # used.archive 已包含 AI 素材与产物（它们计入服务器库配额），另外单列 ai 便于看清构成：
        # by_storage 只来自 tracks 表，ai 是目录求和，两者不可相加（ai 已算在 used.archive 里）。
        return {"by_storage": store.stats(),
                "batch": store.creator_stats(),
                "ai": store.ai_stats(),
                "quota": {"archive": store.quota_of("archive"), "cache": store.quota_of("cache")},
                "used": {"archive": store.storage_used("archive"), "cache": store.storage_used("cache")}}

    @app.post("/v2/maintenance/prune-cache")
    def prune_cache(_ctx: AuthCtx = Depends(guard)):
        return store.prune_cache()

    @app.post("/v2/maintenance/orphans")
    def purge_orphans(_ctx: AuthCtx = Depends(guard)):
        return {"removed": store.purge_orphans()}

    return store
