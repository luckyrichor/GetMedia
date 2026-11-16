"""博主批量任务的 HTTP 层（P2b）。"""
from __future__ import annotations

import logging
from typing import Literal

from fastapi import BackgroundTasks, Depends, Query
from pydantic import BaseModel, ConfigDict, Field

from .creator_jobs import CreatorJobService, JobError, Selection, parse_day
from .errors import ApiError
from .service import AuthCtx

log = logging.getLogger("getmedia.creator_jobs_api")


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
        background.add_task(service.run, job["id"])
        return job

    @app.post("/v2/creators/jobs/{job_id}/retry")
    def retry(job_id: str, background: BackgroundTasks, ctx: AuthCtx = Depends(guard)):
        try:
            out = service.retry(ctx.user_id, _id(job_id))
        except JobError as exc:
            _job_error(exc)
        background.add_task(service.run, out["job"]["id"])
        return out

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
