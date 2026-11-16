"""反馈收件箱（owner 侧读接口，P5c）。

与 ``POST /v2/feedback`` 相反方向：这条通道**跨账号**读所有人的反馈，所以：

- 全部路由都挂 ``require_tier("owner")``，再由 nginx 的 owner 通道挡一层；
- 列表不返回提交者的登录名，只给账号编号前 8 位（与反馈邮件"只发编号"的口径一致）；
- 图片按条目 + 序号取，路径只从库里的白名单取，不接受调用方传文件名（避免目录穿越）；
- 改状态写审计（``feedback_status``），审计里只记编号与目标状态。

状态只有三个：``new`` / ``handled`` / ``ignored``。同一状态重复提交视为成功（幂等）。
"""
from __future__ import annotations

import json
import logging
import time
from pathlib import Path

from fastapi import Depends, Query, Request, Response
from pydantic import BaseModel, ConfigDict
from sqlalchemy import text
from sqlalchemy.engine import Engine

from .errors import ApiError
from .service import AuthCtx

log = logging.getLogger("getmedia.feedback_admin")

STATUSES = ("new", "handled", "ignored")
_IMAGE_TYPES = {".jpg": "image/jpeg", ".png": "image/png"}


class StatusBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    status: str


def _diag_brief(raw: str | None) -> dict:
    """列表用的诊断摘要：只要三条能一眼看出"哪个版本、哪个渠道、什么设备"。"""
    try:
        d = json.loads(raw or "{}")
    except ValueError:
        return {}
    if not isinstance(d, dict):
        return {}
    out = {}
    for k in ("app_version", "channel", "os", "device"):
        v = d.get(k)
        if isinstance(v, str) and v:
            out[k] = v[:64]
    return out


class FeedbackAdmin:
    def __init__(self, engine: Engine, settings, clock=time.time):
        self.engine, self.s, self.clock = engine, settings, clock

    def now(self) -> int:
        return int(self.clock())

    # ---------- 读 ----------
    def summary(self) -> dict:
        with self.engine.begin() as c:
            rows = c.execute(text("SELECT status, COUNT(*) FROM feedback GROUP BY status")).all()
        counts = {s: 0 for s in STATUSES}
        for status, n in rows:
            counts[status if status in counts else "new"] = int(n)
        counts["all"] = sum(counts[s] for s in STATUSES)
        return counts

    def list(self, *, status: str | None = None, category: str | None = None,
             limit: int = 30, offset: int = 0) -> dict:
        where, params = [], {"l": limit, "o": offset}
        # `all` 与不传等价：前端不必为"全部"再写一条不带筛选的调用
        if status and status != "all":
            if status not in STATUSES:
                raise ApiError(422, "validation_error", "状态值不正确")
            where.append("status=:st")
            params["st"] = status
        if category:
            where.append("category=:cat")
            params["cat"] = category[:32]
        clause = f"WHERE {' AND '.join(where)}" if where else ""
        with self.engine.begin() as c:
            total = int(c.execute(text(f"SELECT COUNT(*) FROM feedback {clause}"), params).scalar_one())
            rows = c.execute(text(
                "SELECT id, user_id, created_at, category, text, contact, diagnostics_json, "
                f"image_paths, image_bytes, status, mail_state FROM feedback {clause} "
                "ORDER BY created_at DESC, id DESC LIMIT :l OFFSET :o"), params).all()
        items = []
        for r in rows:
            m = r._mapping
            try:
                paths = json.loads(m["image_paths"] or "[]")
            except ValueError:
                paths = []
            items.append({
                "id": m["id"],
                "created_at": m["created_at"],
                # 账号编号截断：够定位，不够拼出完整身份；完整信息不进邮件也不进这个接口
                "user_ref": (m["user_id"] or "")[:8],
                "category": m["category"],
                "text": m["text"] or "",
                "contact": bool(m["contact"]),
                "image_count": len(paths) if isinstance(paths, list) else 0,
                "image_bytes": m["image_bytes"],
                "status": m["status"] if m["status"] in STATUSES else "new",
                "mail_state": m["mail_state"],
                "diag": _diag_brief(m["diagnostics_json"]),
            })
        return {"items": items, "total": total, "limit": limit, "offset": offset,
                "has_more": offset + len(items) < total, "counts": self.summary()}

    def get(self, fid: str) -> dict:
        with self.engine.begin() as c:
            r = c.execute(text(
                "SELECT id, user_id, created_at, category, text, contact, diagnostics_json, "
                "image_paths, image_bytes, status, mail_state FROM feedback WHERE id=:i"), {"i": fid}).first()
        if r is None:
            raise ApiError(404, "feedback_not_found", "这条反馈不存在")
        m = r._mapping
        try:
            paths = json.loads(m["image_paths"] or "[]")
        except ValueError:
            paths = []
        paths = [p for p in paths if isinstance(p, str)] if isinstance(paths, list) else []
        try:
            diag = json.loads(m["diagnostics_json"] or "{}")
        except ValueError:
            diag = {}
        return {
            "id": m["id"],
            "created_at": m["created_at"],
            "user_ref": (m["user_id"] or "")[:8],
            "category": m["category"],
            "text": m["text"] or "",
            "contact": m["contact"] or "",
            "diag": diag if isinstance(diag, dict) else {},
            "images": [{"index": i, "bytes": self._image_size(p)} for i, p in enumerate(paths)],
            "image_bytes": m["image_bytes"],
            "status": m["status"] if m["status"] in STATUSES else "new",
            "mail_state": m["mail_state"],
        }

    def _image_size(self, name: str) -> int:
        p = self.s.feedback_image_dir / name
        try:
            return p.stat().st_size
        except OSError:
            return 0

    # ---------- 改 ----------
    def set_status(self, fid: str, status: str, *, ip: str, audit) -> dict:
        if status not in STATUSES:
            raise ApiError(422, "validation_error", "状态值不正确")
        with self.engine.begin() as c:
            row = c.execute(text("SELECT status FROM feedback WHERE id=:i"), {"i": fid}).first()
            if row is None:
                raise ApiError(404, "feedback_not_found", "这条反馈不存在")
            if row[0] != status:
                c.execute(text("UPDATE feedback SET status=:s WHERE id=:i"), {"s": status, "i": fid})
            audit(c, "feedback_status", None, ip, f"/v2/feedback/{fid}/status", status)
        return {"id": fid, "status": status}

    # ---------- 图片 ----------
    def image(self, fid: str, index: int) -> tuple[bytes, str]:
        with self.engine.begin() as c:
            r = c.execute(text("SELECT image_paths FROM feedback WHERE id=:i"), {"i": fid}).first()
        if r is None:
            raise ApiError(404, "feedback_not_found", "这条反馈不存在")
        try:
            paths = json.loads(r[0] or "[]")
        except ValueError:
            paths = []
        if not isinstance(paths, list) or not 0 <= index < len(paths):
            raise ApiError(404, "image_not_found", "这张图片不存在")
        name = paths[index]
        if not isinstance(name, str) or "/" in name or "\\" in name or name.startswith("."):
            raise ApiError(404, "image_not_found", "这张图片不存在")
        d: Path = self.s.feedback_image_dir
        p = d / name
        # 双保险：解析后必须仍在图片目录内（名字是入库时生成的 token，正常不会有 `..`）
        try:
            p.resolve().relative_to(d.resolve())
        except (ValueError, OSError):
            raise ApiError(404, "image_not_found", "这张图片不存在") from None
        if not p.is_file():
            raise ApiError(404, "image_not_found", "这张图片已不在服务器上")
        ctype = _IMAGE_TYPES.get(p.suffix.lower(), "application/octet-stream")
        return p.read_bytes(), ctype


def register(app, guard, engine, clock=time.time, *, audit) -> FeedbackAdmin:
    """挂载反馈收件箱路由（全部 owner 级）。"""
    admin = FeedbackAdmin(engine, app.state.settings, clock)
    app.state.feedback_admin = admin

    def _ip(request: Request) -> str:
        from .api import client_ip
        return client_ip(request, app.state.settings.trusted_proxies)

    @app.get("/v2/feedback")
    def feedback_list(request: Request, status: str | None = Query(None, max_length=16),
                      category: str | None = Query(None, max_length=32),
                      limit: int = Query(30, ge=1, le=100), offset: int = Query(0, ge=0, le=100000),
                      _ctx: AuthCtx = Depends(guard)):
        return admin.list(status=status, category=category, limit=limit, offset=offset)

    @app.get("/v2/feedback/{fid}")
    def feedback_get(fid: str, _ctx: AuthCtx = Depends(guard)):
        return admin.get(fid)

    @app.post("/v2/feedback/{fid}/status")
    def feedback_status(fid: str, body: StatusBody, request: Request, ctx: AuthCtx = Depends(guard)):
        out = admin.set_status(fid, body.status, ip=_ip(request),
                               audit=lambda c, e, u, i, p, r: app.state.svc.audit(c, e, u, i, p, r))
        log.info("feedback %s -> %s by %s", fid, body.status, ctx.user_id[:8])
        return out

    @app.get("/v2/feedback/{fid}/images/{index}")
    def feedback_image(fid: str, index: int, _ctx: AuthCtx = Depends(guard)):
        data, ctype = admin.image(fid, index)
        return Response(content=data, media_type=ctype,
                        headers={"Content-Disposition": "inline", "Cache-Control": "no-store"})

    return admin
