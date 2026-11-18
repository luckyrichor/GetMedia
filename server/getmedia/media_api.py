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


class ExtractBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    input: str = Field(min_length=1, max_length=4000, description="作品链接或分享文案")
    media_type: Literal["audio", "video"] = "video"
    storage: Literal["metadata", "archive", "cache"] = "metadata"


class TrackPatchBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    title: str | None = Field(None, min_length=1, max_length=200)
    uploader: str | None = Field(None, max_length=100)
    pinned: bool | None = None


def _translate(exc: Exception) -> None:
    if isinstance(exc, MediaError):
        raise ApiError(exc.status, exc.code, exc.detail) from exc
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


def _attachment_name(title: str | None, track_id: int, ext: str) -> str:
    """把标题变成可安全放进响应头的文件名。

    标题是用户/平台给的内容，可能带换行、引号、斜杠：不清洗会直接构成响应头注入，
    也会让浏览器存出奇怪的路径。这里只保留可打印字符，其余换成下划线。
    """
    cleaned = _UNSAFE_NAME.sub("_", (title or "").strip())
    cleaned = re.sub(r"\s+", " ", cleaned).strip(" ._")
    if not cleaned:
        cleaned = f"media-{track_id}"
    return f"{cleaned[:60]}.{ext.lstrip('.') or 'mp4'}"


def _disposition(name: str, track_id: int, ext: str) -> str:
    """RFC 6266：ASCII 兜底名 + UTF-8 真名（中文标题不会变成乱码）。"""
    fallback = f"media-{track_id}.{ext.lstrip('.') or 'mp4'}"
    return f"attachment; filename=\"{fallback}\"; filename*=UTF-8''{quote(name, safe='')}"


def _file_response(path: Path, range_header: str | None, media_type: str,
                   disposition: str | None = None) -> Response:
    size = path.stat().st_size
    ctype = "video/mp4" if media_type == "video" else "audio/mp4"
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

    def _run_job(job_id: int, user_id: str, input_: str, media_type: str, storage: str) -> None:
        store.job_update(job_id, status="running", started_at=store.now())
        try:
            m = resolver.resolve(input_, media_type=media_type)
        except ResolveError as exc:
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
        background.add_task(_run_job, job_id, ctx.user_id, body.input, body.media_type, body.storage)
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

    @app.get("/v2/tracks/{track_id}")
    def get_track(track_id: str, ctx: AuthCtx = Depends(guard)):
        try:
            return store.get(ctx.user_id, _tid(track_id), include_deleted=True)
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
              ctx: AuthCtx = Depends(guard)):
        """``?download=1`` 时按附件下发（浏览器直接存盘），否则内联给播放器。

        两种来源都支持：服务器有文件就直读，没有就当场解析并中转——所以「仅元数据」的
        条目也能下载，代价是每次都要现解析、且拿不到总长度。
        """
        tid = _tid(track_id)
        try:
            track = store.get(ctx.user_id, tid, include_deleted=True)
            tf = store.file_for(ctx.user_id, tid)
        except MediaError as exc:
            _translate(exc)
        if tf is not None:
            store.touch(tid)
            disposition = None
            if download:
                ext = tf.path.suffix
                disposition = _disposition(_attachment_name(track["title"], tid, ext), tid, ext)
            return _file_response(tf.path, range_header, track["media_type"], disposition)

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
            headers["Content-Disposition"] = _disposition(
                _attachment_name(track["title"], tid, m.ext), tid, m.ext)

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
