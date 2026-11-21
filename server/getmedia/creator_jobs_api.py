"""博主批量任务的 HTTP 层（P2b）。"""
from __future__ import annotations

import json
import logging
from typing import Literal

import httpx
from fastapi import BackgroundTasks, Depends, Header, Query
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field

from .creator_jobs import CreatorJobService, JobError, Selection, parse_day
from .creator_links import LinkError, require_numeric_id
from .errors import ApiError
from .media_api import attachment_disposition, attachment_name
from .service import AuthCtx

log = logging.getLogger("getmedia.creator_jobs_api")

# 取流用的 UA：与平台网页端一致，抖音 CDN 会看这个
_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36")
_REFERER = {"douyin": "https://www.douyin.com/", "bilibili": "https://www.bilibili.com/"}


class JobCreateBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    url: str = Field(min_length=1, max_length=2000)
    platform: Literal["bilibili", "douyin"] | None = None
    mode: Literal["all", "date_range", "latest_n"] = "latest_n"
    since: str | None = Field(None, description="YYYY-MM-DD（北京时间）")
    until: str | None = Field(None, description="YYYY-MM-DD（北京时间，含当日）")
    n: int | None = Field(None, ge=1, le=100000)
    include_pinned: bool = True
    storage: Literal["phone", "metadata", "archive", "cache"] = "phone"
    media_type: Literal["audio", "video"] = "video"


class ItemStateBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    state: Literal["pending", "downloading", "done", "failed", "skipped"]
    error: str | None = Field(None, max_length=500)
    track_id: int | None = None


class ImportItem(BaseModel):
    model_config = ConfigDict(extra="forbid")
    video_id: str = Field(min_length=1, max_length=64)
    title: str = Field("", max_length=200)
    platform: str = Field("unknown", max_length=16)
    duration_sec: float | None = None
    published_at: int | None = None
    is_pinned: bool = False
    cover_url: str | None = None
    source_url: str = Field("", max_length=500)


class ImportBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    items: list[ImportItem] = Field(min_length=1, max_length=2000)


def register(app, guard, engine, clock, *, service: CreatorJobService | None = None,
             lister=None, resolver=None, pinned=None, cookies=None) -> CreatorJobService:
    service = service or CreatorJobService(engine, app.state.settings, clock,
                                          resolve_fn=resolver, window_fn=lister, pinned_fn=pinned,
                                          cookie_fn=cookies)
    app.state.creator_jobs = service

    def _id(raw: str) -> int:
        try:
            return int(raw)
        except (TypeError, ValueError):
            raise ApiError(404, "job_not_found", "任务不存在") from None

    def _job_error(exc: JobError) -> None:
        raise ApiError(exc.status, exc.code, exc.detail) from exc

    @app.post("/v2/creators/jobs", status_code=202)
    def create_job(body: JobCreateBody, background: BackgroundTasks, ctx: AuthCtx = Depends(guard)):
        try:
            require_numeric_id(body.platform, body.url)
            selection = Selection(
                mode=body.mode,
                since=parse_day(body.since) if body.since else None,
                until=parse_day(body.until, end=True) if body.until else None,
                n=body.n,
                include_pinned=body.include_pinned,
            )
            job = service.create(ctx.user_id, url=body.url, selection=selection, storage=body.storage,
                                 media_type=body.media_type, platform=body.platform)
        except JobError as exc:
            _job_error(exc)
        except LinkError as exc:
            raise ApiError(400, "creator_input", str(exc)) from exc
        background.add_task(service.run, job["id"])
        return job

    @app.get("/v2/creators/jobs")
    def list_jobs(status: str | None = None, limit: int = Query(50, ge=1, le=200),
                  ctx: AuthCtx = Depends(guard)):
        return {"jobs": service.list_jobs(ctx.user_id, status=status, limit=limit)}

    @app.get("/v2/creators/jobs/{job_id}")
    def get_job(job_id: str, ctx: AuthCtx = Depends(guard)):
        try:
            return service.get(ctx.user_id, _id(job_id))
        except JobError as exc:
            _job_error(exc)

    @app.get("/v2/creators/jobs/{job_id}/items")
    def job_items(job_id: str, state: str | None = None, limit: int = Query(100, ge=1, le=500),
                  offset: int = Query(0, ge=0), ctx: AuthCtx = Depends(guard)):
        try:
            return service.items(ctx.user_id, _id(job_id), state=state, limit=limit, offset=offset)
        except JobError as exc:
            _job_error(exc)

    @app.post("/v2/creators/jobs/{job_id}/cancel")
    def cancel(job_id: str, ctx: AuthCtx = Depends(guard)):
        try:
            return service.cancel(ctx.user_id, _id(job_id))
        except JobError as exc:
            _job_error(exc)

    @app.post("/v2/creators/jobs/{job_id}/pause")
    def pause(job_id: str, ctx: AuthCtx = Depends(guard)):
        try:
            return service.pause(ctx.user_id, _id(job_id))
        except JobError as exc:
            _job_error(exc)

    @app.post("/v2/creators/jobs/{job_id}/resume")
    def resume(job_id: str, background: BackgroundTasks, ctx: AuthCtx = Depends(guard)):
        try:
            job = service.resume(ctx.user_id, _id(job_id))
        except JobError as exc:
            _job_error(exc)
        except LinkError as exc:
            raise ApiError(400, "creator_input", str(exc)) from exc
        background.add_task(service.run, job["id"])
        return job

    @app.post("/v2/creators/jobs/{job_id}/retry")
    def retry(job_id: str, background: BackgroundTasks, ctx: AuthCtx = Depends(guard)):
        try:
            out = service.retry(ctx.user_id, _id(job_id))
        except JobError as exc:
            _job_error(exc)
        background.add_task(service.run, out["job"]["id"], refresh=out.get("rescan", False))
        return out

    def _forward(urls: list[str], *, platform: str, range_header: str | None,
                 disposition: str, media_type: str):
        """按顺序试候选播放地址，第一个能出流的转给浏览器；全失败返回 None。

        用服务端转发而不是 302：直链是带签名的 CDN 地址，浏览器直接跟随没有
        Referer/UA 常被判 403，也拿不到「标题.mp4」这个文件名。转发多耗一次出网带宽，
        但换来能下、文件名对。
        """
        headers = {"User-Agent": _UA, "Referer": _REFERER.get(platform, _REFERER["douyin"])}
        for url in urls:
            client = httpx.Client(timeout=30.0, follow_redirects=True)
            try:
                h = dict(headers)
                if range_header:
                    h["Range"] = range_header
                resp = client.send(client.build_request("GET", url, headers=h), stream=True)
            except Exception as exc:  # noqa: BLE001 - 换个域名再试
                client.close()
                log.info("直链取流失败（%s）：%s", url.split("?")[0], type(exc).__name__)
                continue
            ctype = (resp.headers.get("content-type") or "").lower()
            if resp.status_code not in (200, 206) or not (
                    ctype.startswith(("video/", "audio/")) or ctype == "application/octet-stream"):
                # 候选中可能有网页地址，返回的是 HTML，别当媒体转发出去
                log.info("直链不可用：%s %s", resp.status_code, url.split("?")[0])
                resp.close()
                client.close()
                continue

            def body(resp=resp, client=client):
                try:
                    yield from resp.iter_bytes()
                finally:
                    resp.close()
                    client.close()

            out = {"Accept-Ranges": "bytes", "Content-Disposition": disposition}
            for k in ("Content-Range", "Content-Length", "Content-Type"):
                if k in resp.headers:
                    out[k] = resp.headers[k]
            out.setdefault("Content-Type", "video/mp4" if media_type == "video" else "audio/mp4")
            return StreamingResponse(body(), status_code=resp.status_code, headers=out)
        return None

    @app.get("/v2/creators/jobs/{job_id}/items/{video_id}/file")
    def download_item(job_id: str, video_id: str,
                      range_header: str | None = Header(default=None, alias="Range"),
                      ctx: AuthCtx = Depends(guard)):
        """把任务里的某条作品直接下载到打开网页的设备。

        优先用扫描时存下的播放地址（抖音列表响应自带，零额外解析）；过期或没有时，
        B 站可以现解析，抖音要开浏览器——那条路太慢，改为提示重扫任务。
        """
        jid = _id(job_id)
        try:
            job = service.get(ctx.user_id, jid)
            item = service.item(ctx.user_id, jid, video_id)
        except JobError as exc:
            _job_error(exc)

        urls: list[str] = []
        if item.get("play_urls"):
            try:
                urls = [u for u in json.loads(item["play_urls"])
                        if isinstance(u, str) and u.startswith("http")]
            except (TypeError, ValueError):
                urls = []

        media_type = job.get("media_type") or "video"
        ext = "m4a" if media_type == "audio" else "mp4"
        disposition = attachment_disposition(attachment_name(item["title"], jid, ext), jid, ext)
        forwarded = _forward(urls, platform=item["platform"], range_header=range_header,
                             disposition=disposition, media_type=media_type)
        if forwarded is not None:
            return forwarded

        if item["platform"] == "bilibili" and item.get("source_url"):
            # 注意：本函数签名里的 resolver 是「博主链接 → CreatorRef」，不是媒体解析器。
            # 媒体解析器挂在 app.state.extractor 上（与 /v2/media 共用同一个实例）。
            media_resolver = getattr(app.state, "extractor", None)
            if media_resolver is not None:
                try:
                    media = media_resolver.resolve(item["source_url"], media_type=media_type)
                    resp = media_resolver.open_stream(media.sources[0], range_header=range_header)
                except Exception as exc:  # noqa: BLE001 - 上游问题统一报可重试
                    raise ApiError(502, "media_upstream_failed", "解析作品地址失败，请稍后重试") from exc
                if resp.status_code not in (200, 206):
                    resp.close()
                    raise ApiError(502, "media_upstream_failed", "平台拒绝取流，请稍后重试")
                disposition = attachment_disposition(
                    attachment_name(item["title"], jid, media.ext), jid, media.ext)
                headers = {"Accept-Ranges": "bytes", "Content-Disposition": disposition}
                for k in ("Content-Range", "Content-Length", "Content-Type"):
                    if k in resp.headers:
                        headers[k] = resp.headers[k]

                def body(resp=resp):
                    try:
                        yield from resp.iter_bytes()
                    finally:
                        resp.close()

                return StreamingResponse(body(), status_code=resp.status_code, headers=headers)

        raise ApiError(409, "media_link_expired",
                       "这条的播放地址已失效（作品可能已删除或设为私密）。"
                       "可对该任务点「重试」刷新播放地址后再下载")

    @app.post("/v2/creators/jobs/{job_id}/items/{video_id}/state")
    def item_state(job_id: str, video_id: str, body: ItemStateBody, ctx: AuthCtx = Depends(guard)):
        try:
            return service.set_item_state(ctx.user_id, _id(job_id), video_id, state=body.state,
                                          error=body.error, track_id=body.track_id)
        except JobError as exc:
            _job_error(exc)

    @app.post("/v2/creators/jobs/{job_id}/items:import")
    def items_import(job_id: str, body: ImportBody, ctx: AuthCtx = Depends(guard)):
        try:
            return service.import_items(ctx.user_id, _id(job_id), [i.model_dump() for i in body.items])
        except JobError as exc:
            _job_error(exc)

    return service


__all__ = ["register", "JobCreateBody", "ItemStateBody", "ImportBody"]
