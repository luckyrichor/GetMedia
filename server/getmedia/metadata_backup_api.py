"""曲库清单备份的 HTTP 层（P0-1 之后的元数据备份）。

五条路由全部走免费通道 + 登录态：备份是"重装后把清单捞回来"，免费账号同样需要，
所以登记在 tiers.FREE_ROUTES，而不是挂 owner 依赖。限频在应用层（按账号），不在 nginx。
"""
from __future__ import annotations

import re

from fastapi import Depends, Request, Response

from .errors import ApiError
from .metadata_backup import GZIP_MAX, MetadataBackupService
from .service import AuthCtx

_CTRL = re.compile(r"[\x00-\x1f\x7f]")
GZIP_TYPE = "application/gzip"


def _header(request: Request, name: str, limit: int) -> str:
    return _CTRL.sub("", request.headers.get(name, ""))[:limit]


async def _read_limited(request: Request, limit: int) -> bytes:
    """边读边卡上限：不先信任 Content-Length，也不把超大请求整个读进内存。"""
    chunks: list[bytes] = []
    total = 0
    async for chunk in request.stream():
        total += len(chunk)
        if total > limit:
            raise ApiError(413, "backup_too_large", "备份内容过大")
        chunks.append(chunk)
    return b"".join(chunks)


def register(app, guard, engine, clock) -> MetadataBackupService:
    service = MetadataBackupService(engine, clock)
    app.state.metadata_backup = service

    @app.get("/account/metadata-backups")
    def list_backups(ctx: AuthCtx = Depends(guard)):
        """列表不含 payload：列表页只看得到条数、字节、时间和机型。"""
        return service.list(ctx.user_id)

    @app.post("/account/metadata-backups", status_code=201)
    async def create_backup(request: Request, ctx: AuthCtx = Depends(guard)):
        content_type = (request.headers.get("content-type") or "").split(";")[0].strip().lower()
        if content_type != GZIP_TYPE:
            raise ApiError(415, "unsupported_media_type", "备份必须是 gzip 压缩的内容")
        body = await _read_limited(request, GZIP_MAX)
        return service.create(
            ctx.user_id,
            body,
            app_version=_header(request, "X-GetMedia-App", 32),
            device=_header(request, "X-GetMedia-Device", 64),
        )

    @app.get("/account/metadata-backups/{backup_id}")
    def download_backup(backup_id: str, ctx: AuthCtx = Depends(guard)):
        body, digest = service.payload(ctx.user_id, backup_id)
        return Response(
            content=body,
            media_type=GZIP_TYPE,
            headers={"X-Backup-Sha256": digest, "Content-Length": str(len(body))},
        )

    @app.delete("/account/metadata-backups/{backup_id}")
    def delete_backup(backup_id: str, ctx: AuthCtx = Depends(guard)):
        deleted = service.delete(ctx.user_id, backup_id)
        if not deleted:
            raise ApiError(404, "backup_not_found", "备份不存在")
        return {"deleted": deleted}

    @app.delete("/account/metadata-backups")
    def delete_all_backups(ctx: AuthCtx = Depends(guard)):
        return {"deleted": service.delete_all(ctx.user_id)}

    return service


__all__ = ["register"]
