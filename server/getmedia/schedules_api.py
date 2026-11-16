"""定时抓取的 HTTP 层（P2c）。"""
from __future__ import annotations

import logging
from typing import Literal

from fastapi import BackgroundTasks, Depends, Response
from pydantic import BaseModel, ConfigDict, Field

from .errors import ApiError
from .schedules import MAX_INTERVAL, MIN_INTERVAL, ScheduleError, ScheduleService
from .service import AuthCtx

log = logging.getLogger("getmedia.schedules_api")


class ScheduleCreateBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1, max_length=80)
    platform: Literal["bilibili", "douyin"] = "bilibili"
    input: str = Field(min_length=1, max_length=2000, description="博主主页链接或 UID/抖音号")
    mode: Literal["all", "date_range", "latest_n"] = "latest_n"
    since: str | None = None
    until: str | None = None
    n: int | None = Field(None, ge=1, le=100000)
    interval_minutes: int = Field(360, ge=MIN_INTERVAL, le=MAX_INTERVAL)
    include_pinned: bool = True
    storage: Literal["phone", "metadata", "archive", "cache"] = "phone"
    media_type: Literal["audio", "video"] = "video"


class SchedulePatchBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str | None = Field(None, min_length=1, max_length=80)
    input: str | None = Field(None, min_length=1, max_length=2000)
    mode: Literal["all", "date_range", "latest_n"] | None = None
    since: str | None = None
    until: str | None = None
    n: int | None = Field(None, ge=1, le=100000)
    interval_minutes: int | None = Field(None, ge=MIN_INTERVAL, le=MAX_INTERVAL)
    include_pinned: bool | None = None
    storage: Literal["phone", "metadata", "archive", "cache"] | None = None
    media_type: Literal["audio", "video"] | None = None
    enabled: bool | None = None


def register(app, guard, engine, clock, *, jobs) -> ScheduleService:
    service = ScheduleService(engine, app.state.settings, clock, jobs=jobs)
    app.state.schedules = service

    def _id(raw: str) -> int:
        try:
            return int(raw)
        except (TypeError, ValueError):
            raise ApiError(404, "schedule_not_found", "定时任务不存在") from None

    def _err(exc: ScheduleError) -> None:
        raise ApiError(exc.status, exc.code, exc.detail) from exc

    @app.get("/v2/schedules")
    def list_schedules(ctx: AuthCtx = Depends(guard)):
        return {"schedules": service.list(ctx.user_id)}

    @app.post("/v2/schedules", status_code=201)
    def create_schedule(body: ScheduleCreateBody, ctx: AuthCtx = Depends(guard)):
        try:
            return service.create(ctx.user_id, name=body.name, platform=body.platform, input_=body.input,
                                  mode=body.mode, interval_minutes=body.interval_minutes, n=body.n,
                                  since=body.since, until=body.until, include_pinned=body.include_pinned,
                                  storage=body.storage, media_type=body.media_type)
        except ScheduleError as exc:
            _err(exc)

    @app.get("/v2/schedules/{schedule_id}")
    def get_schedule(schedule_id: str, ctx: AuthCtx = Depends(guard)):
        try:
            return service.get(ctx.user_id, _id(schedule_id))
        except ScheduleError as exc:
            _err(exc)

    @app.patch("/v2/schedules/{schedule_id}")
    def patch_schedule(schedule_id: str, body: SchedulePatchBody, ctx: AuthCtx = Depends(guard)):
        try:
            return service.patch(ctx.user_id, _id(schedule_id), **body.model_dump())
        except ScheduleError as exc:
            _err(exc)

    @app.delete("/v2/schedules/{schedule_id}", status_code=204)
    def delete_schedule(schedule_id: str, ctx: AuthCtx = Depends(guard)):
        try:
            service.delete(ctx.user_id, _id(schedule_id))
        except ScheduleError as exc:
            _err(exc)
        return Response(status_code=204)

    @app.get("/v2/schedules/{schedule_id}/runs")
    def schedule_runs(schedule_id: str, ctx: AuthCtx = Depends(guard)):
        try:
            return {"runs": service.runs(ctx.user_id, _id(schedule_id))}
        except ScheduleError as exc:
            _err(exc)

    @app.post("/v2/schedules/{schedule_id}/run-once", status_code=202)
    def run_once(schedule_id: str, background: BackgroundTasks, ctx: AuthCtx = Depends(guard)):
        try:
            sch = service.get(ctx.user_id, _id(schedule_id))
        except ScheduleError as exc:
            _err(exc)
        background.add_task(service.run_once, ctx.user_id, sch["id"])
        return {"scheduled": True, "schedule_id": sch["id"]}

    return service


__all__ = ["register", "ScheduleCreateBody", "SchedulePatchBody"]
