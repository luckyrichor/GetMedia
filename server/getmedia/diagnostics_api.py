"""失败上报的 HTTP 层（P2f）。"""
from __future__ import annotations

from fastapi import Depends, Query
from pydantic import BaseModel, ConfigDict, Field

from .diagnostics import DiagnosticsService, ReportError
from .errors import ApiError
from .service import AuthCtx


class ReportBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    platform: str = Field(max_length=32)
    video_id: str = Field(max_length=64)
    stage: str = Field("extract", max_length=32)
    error_code: str = Field(max_length=64)
    detail: str = Field("", max_length=2000)
    client_version: str = Field("", max_length=32)
    track_id: int | None = None


def register(app, guard, engine, clock) -> DiagnosticsService:
    service = DiagnosticsService(engine, app.state.settings, clock)
    app.state.diagnostics = service

    @app.post("/v2/diagnostics/extractions", status_code=201)
    def report(body: ReportBody, ctx: AuthCtx = Depends(guard)):
        return service.record(ctx.user_id, platform=body.platform, video_id=body.video_id,
                              stage=body.stage, error_code=body.error_code, detail=body.detail,
                              client_version=body.client_version, track_id=body.track_id)

    @app.get("/v2/diagnostics/extractions")
    def list_reports(limit: int = Query(100, ge=1, le=500), platform: str | None = None,
                     ctx: AuthCtx = Depends(guard)):
        return {"reports": service.list(ctx.user_id, limit=limit, platform=platform)}

    @app.get("/v2/diagnostics/extractions/{report_id}")
    def get_report(report_id: str, ctx: AuthCtx = Depends(guard)):
        try:
            return service.get(ctx.user_id, int(report_id))
        except (ValueError, ReportError) as exc:
            if isinstance(exc, ReportError):
                raise ApiError(exc.status, exc.code, exc.detail) from exc
            raise ApiError(404, "report_not_found", "上报记录不存在") from None

    @app.get("/v2/diagnostics/summary")
    def summary(days: int = Query(7, ge=1, le=90), ctx: AuthCtx = Depends(guard)):
        return {"days": days, "groups": service.summary(ctx.user_id, days)}

    return service


__all__ = ["register", "ReportBody"]
