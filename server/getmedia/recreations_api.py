"""AI 视频重建的 HTTP 层（P2g）。"""
from __future__ import annotations

import logging

from fastapi import Depends, File, Header, Query, Response, UploadFile
from pydantic import BaseModel, ConfigDict, Field

from .errors import ApiError
from .media_api import _file_response
from .recreations import RecreationError, RecreationService

log = logging.getLogger("getmedia.recreations_api")


class DescriptionBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    description: str = Field(max_length=4000)


def register(app, guard, engine, clock, *, media=None, audit=None) -> RecreationService:
    service = RecreationService(engine, app.state.settings, clock, media=media)
    app.state.recreations = service

    def _err(exc: RecreationError) -> None:
        raise ApiError(exc.status, exc.code, exc.detail) from exc

    def _id(raw: str) -> int:
        try:
            return int(raw)
        except (TypeError, ValueError):
            raise ApiError(404, "recreation_not_found", "重建项目不存在") from None

    @app.get("/v2/recreations/capabilities")
    def capabilities(_ctx=Depends(guard)):
        return service.capabilities()

    @app.get("/v2/recreations")
    def list_recreations(_ctx=Depends(guard)):
        ctx = _ctx
        return {"recreations": service.list(ctx.user_id)}

    @app.get("/v2/recreations/{pid}")
    def get_recreation(pid: str, ctx=Depends(guard)):
        try:
            return service.get(ctx.user_id, _id(pid))
        except RecreationError as exc:
            _err(exc)

    @app.post("/v2/recreations/upload", status_code=201)
    async def upload(ctx=Depends(guard), file: UploadFile = File(...)):
        chunks, total = [], 0
        limit = app.state.settings.ai_max_upload_bytes
        while True:
            chunk = await file.read(1024 * 1024)
            if not chunk:
                break
            total += len(chunk)
            if total > limit:
                raise ApiError(413, "file_too_large", f"文件不能超过 {limit // 1024**2} MB")
            chunks.append(chunk)
        try:
            return service.create_upload(ctx.user_id, filename=file.filename or "upload.mp4",
                                         data=b"".join(chunks))
        except RecreationError as exc:
            _err(exc)

    @app.post("/v2/recreations/from-track/{track_id}", status_code=201)
    def from_track(track_id: str, ctx=Depends(guard)):
        try:
            return service.create_from_track(ctx.user_id, int(track_id))
        except ValueError:
            raise ApiError(404, "track_not_found", "媒体不存在") from None
        except RecreationError as exc:
            _err(exc)

    @app.post("/v2/recreations/gc")
    def gc(days: int | None = Query(default=None, ge=1, le=365),
           dry_run: bool = Query(default=False), ctx=Depends(guard)):
        out = service.gc(days=days, dry_run=dry_run)
        if audit is not None and not dry_run:
            with engine.begin() as c:
                audit(c, "ai_gc", ctx.user_id, "", "/v2/recreations/gc", "ok")
        return out

    @app.get("/v2/recreations/{pid}/source")
    def source(pid: str, range_header: str | None = Header(default=None, alias="Range"), ctx=Depends(guard)):
        try:
            path = service.source_file(ctx.user_id, _id(pid))
        except RecreationError as exc:
            _err(exc)
        return _file_response(path, range_header, "video")

    @app.put("/v2/recreations/{pid}/description")
    def set_description(pid: str, body: DescriptionBody, ctx=Depends(guard)):
        try:
            return service.set_description(ctx.user_id, _id(pid), body.description)
        except RecreationError as exc:
            _err(exc)

    @app.post("/v2/recreations/{pid}/analyze", status_code=202)
    def analyze(pid: str, ctx=Depends(guard)):
        try:
            return service.enqueue(ctx.user_id, _id(pid), "analyze")
        except RecreationError as exc:
            _err(exc)

    @app.post("/v2/recreations/{pid}/generate", status_code=202)
    def generate(pid: str, ctx=Depends(guard)):
        try:
            return service.enqueue(ctx.user_id, _id(pid), "generate")
        except RecreationError as exc:
            _err(exc)

    @app.post("/v2/recreations/{pid}/runs/{rid}/cancel")
    def cancel(pid: str, rid: str, ctx=Depends(guard)):
        try:
            return service.cancel_run(ctx.user_id, _id(pid), int(rid))
        except ValueError:
            raise ApiError(404, "run_not_found", "重建任务不存在") from None
        except RecreationError as exc:
            _err(exc)

    @app.get("/v2/recreations/{pid}/runs/{rid}/video")
    def video(pid: str, rid: str, range_header: str | None = Header(default=None, alias="Range"),
              ctx=Depends(guard)):
        try:
            path = service.output_file(ctx.user_id, _id(pid), int(rid))
        except ValueError:
            raise ApiError(404, "run_not_found", "重建任务不存在") from None
        except RecreationError as exc:
            _err(exc)
        return _file_response(path, range_header, "video")

    @app.post("/v2/recreations/{pid}/runs/{rid}/save")
    def save(pid: str, rid: str, ctx=Depends(guard)):
        try:
            return service.save_output(ctx.user_id, _id(pid), int(rid))
        except ValueError:
            raise ApiError(404, "run_not_found", "重建任务不存在") from None
        except RecreationError as exc:
            _err(exc)

    return service


__all__ = ["register", "DescriptionBody"]
