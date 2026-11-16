"""提取 Agent（P2d）：会话、消息、偏好记忆。

模型调用是可插拔的：``brain`` 未注入时用确定性规则应答——不假装是模型，
也不在没有密钥的情况下把请求发到外网。真模型接入放在 P2g 之后单独评估。
"""
from __future__ import annotations

import json
import uuid
import logging
from typing import Callable

from sqlalchemy import text
from sqlalchemy.engine import Engine

from .config import Settings

log = logging.getLogger("getmedia.agent")

MAX_MESSAGE = 4000


class AgentError(Exception):
    def __init__(self, code: str, detail: str, status: int = 400):
        super().__init__(detail)
        self.code, self.detail, self.status = code, detail, status


def _rules_brain(message: str, context: dict) -> str:
    """没有模型时的确定性应答：只讲事实与下一步能做什麼，不编造。"""
    m = message.lower()
    stats = context.get("stats", {})
    if any(k in message for k in ("批量", "博主", "主页", "UP")):
        return ("批量提取要先建任务：POST /v2/creators/jobs，mode 可选 all / date_range / latest_n，"
                "置顶作品会带 is_pinned 但不插队。任务建好后手机按 /items?state=pending 逐条领取，"
                "完成后回传 /items/{video_id}/state。")
    if any(k in message for k in ("定时", "每天", "定期")):
        return ("定时用 /v2/schedules：name + input + mode + interval_minutes；触发时只是建一个批量任务，"
                "下载进度仍由任务本身记录。可用 /v2/schedules/{id}/run-once 立即跑一次。")
    if any(k in message for k in ("多少", "统计", "库", "空间")):
        return (f"当前库里：{stats.get('tracks', 0)} 条媒体，"
                f"其中归档 {stats.get('archive_bytes', 0)} 字节、缓存 {stats.get('cache_bytes', 0)} 字节。"
                "详细配额见 /v2/maintenance/storage。")
    if any(k in message for k in ("授权", "cookie", "登录")):
        return ("平台授权在 /account/platform-auth/start 发起，支持网页登录取 Cookie 与导入 Cookie 两种方式；"
                "Cookie 用 AES-256-GCM 加密保存，服务端不回显明文。")
    if any(k in message for k in ("失败", "报错", "error")):
        return ("提取失败请把平台、作品号、阶段与错误码回传 /v2/diagnostics/extractions，"
                "我会按平台与错误码归类，方便定位是风控、授权过期还是解析规则变了。")
    return (f"我可以帮你：批量列出博主作品、建定时任务、查媒体库与配额、查看授权状态。"
            f"当前库里有 {stats.get('tracks', 0)} 条媒体。直接说你要做什么即可。")


class AgentService:
    def __init__(self, engine: Engine, settings: Settings, clock, *, brain: Callable | None = None,
                 media=None, creator_jobs=None):
        self.engine, self.s, self.clock = engine, settings, clock
        self.brain = brain or _rules_brain
        self.media, self.creator_jobs = media, creator_jobs

    def now(self) -> int:
        return int(self.clock())

    # ---------- 会话 ----------
    def list_conversations(self, user_id: str, limit: int = 50) -> list[dict]:
        with self.engine.begin() as c:
            rows = c.execute(text("SELECT id, title, created_at, updated_at FROM agent_conversations"
                                  " WHERE user_id=:u ORDER BY updated_at DESC LIMIT :l"),
                             {"u": user_id, "l": limit}).all()
        return [dict(r._mapping) for r in rows]

    def create_conversation(self, user_id: str, title: str = "") -> dict:
        cid, now = "c_" + uuid.uuid4().hex[:20], self.now()
        with self.engine.begin() as c:
            c.execute(text("INSERT INTO agent_conversations(id, user_id, title, created_at, updated_at)"
                           " VALUES(:i,:u,:t,:n,:n)"),
                      {"i": cid, "u": user_id, "t": title[:80], "n": now})
        return {"id": cid, "title": title[:80], "created_at": now, "updated_at": now}

    def _owned(self, user_id: str, cid: str) -> dict:
        with self.engine.begin() as c:
            row = c.execute(text("SELECT id, title, created_at, updated_at FROM agent_conversations"
                                 " WHERE id=:i AND user_id=:u"), {"i": cid, "u": user_id}).first()
        if row is None:
            raise AgentError("conversation_not_found", "会话不存在", 404)
        return dict(row._mapping)

    def get_conversation(self, user_id: str, cid: str, limit: int = 200) -> dict:
        conv = self._owned(user_id, cid)
        with self.engine.begin() as c:
            rows = c.execute(text("SELECT id, role, content, meta_json, created_at FROM agent_messages"
                                  " WHERE conversation_id=:c ORDER BY id DESC LIMIT :l"),
                             {"c": cid, "l": limit}).all()
        msgs = []
        for r in reversed(rows):
            m = dict(r._mapping)
            try:
                m["meta"] = json.loads(m.pop("meta_json") or "{}")
            except ValueError:
                m["meta"] = {}
            msgs.append(m)
        conv["messages"] = msgs
        return conv

    def delete_conversation(self, user_id: str, cid: str) -> None:
        self._owned(user_id, cid)
        with self.engine.begin() as c:
            c.execute(text("DELETE FROM agent_conversations WHERE id=:i AND user_id=:u"),
                      {"i": cid, "u": user_id})

    # ---------- 对话 ----------
    def chat(self, user_id: str, message: str, *, conversation_id: str | None = None) -> dict:
        message = (message or "").strip()
        if not message:
            raise AgentError("empty_message", "消息不能为空")
        if len(message) > MAX_MESSAGE:
            raise AgentError("message_too_long", f"消息不能超过 {MAX_MESSAGE} 字")
        if conversation_id:
            self._owned(user_id, conversation_id)
            cid = conversation_id
        else:
            cid = self.create_conversation(user_id, title=message[:30])["id"]

        now = self.now()
        with self.engine.begin() as c:
            c.execute(text("INSERT INTO agent_messages(conversation_id, role, content, created_at)"
                           " VALUES(:c,'user',:t,:n)"), {"c": cid, "t": message, "n": now})

        context = self._context(user_id)
        try:
            reply = self.brain(message, context)
        except Exception as exc:  # noqa: BLE001 - 模型/规则出错不能吞掉用户消息
            log.warning("agent brain 失败：%s", type(exc).__name__)
            reply = "我这边处理出了点问题，请稍后再试；你的消息已经记录在会话里。"
        with self.engine.begin() as c:
            c.execute(text("INSERT INTO agent_messages(conversation_id, role, content, meta_json, created_at)"
                           " VALUES(:c,'assistant',:t,:m,:n)"),
                      {"c": cid, "t": reply[:MAX_MESSAGE], "m": json.dumps({"engine": "rules"}, ensure_ascii=False),
                       "n": self.now()})
            c.execute(text("UPDATE agent_conversations SET updated_at=:n WHERE id=:c"), {"n": self.now(), "c": cid})
        return {"conversation_id": cid, "reply": reply, "created_at": now}

    def _context(self, user_id: str) -> dict:
        stats = {"tracks": 0, "archive_bytes": 0, "cache_bytes": 0}
        if self.media is not None:
            used = self.media.stats()
            stats = {"tracks": sum(v["tracks"] for v in used.values()),
                     "archive_bytes": used.get("archive", {}).get("bytes", 0),
                     "cache_bytes": used.get("cache", {}).get("bytes", 0)}
        return {"stats": stats, "now": self.now()}

    # ---------- 偏好记忆 ----------
    def list_memories(self, user_id: str) -> list[dict]:
        with self.engine.begin() as c:
            rows = c.execute(text("SELECT key, value, created_at, updated_at FROM agent_memories"
                                  " WHERE user_id=:u ORDER BY key"), {"u": user_id}).all()
        return [dict(r._mapping) for r in rows]

    def put_memory(self, user_id: str, key: str, value: str) -> dict:
        key = (key or "").strip()[:64]
        if not key:
            raise AgentError("bad_key", "记忆的键不能为空")
        now = self.now()
        with self.engine.begin() as c:
            c.execute(text("INSERT INTO agent_memories(user_id, key, value, created_at, updated_at)"
                           " VALUES(:u,:k,:v,:n,:n)"
                           " ON CONFLICT(user_id,key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at"),
                      {"u": user_id, "k": key, "v": value[:2000], "n": now})
        return {"key": key, "value": value[:2000], "updated_at": now}

    def delete_memory(self, user_id: str, key: str) -> None:
        with self.engine.begin() as c:
            n = c.execute(text("DELETE FROM agent_memories WHERE user_id=:u AND key=:k"),
                          {"u": user_id, "k": key}).rowcount
        if not n:
            raise AgentError("memory_not_found", "没有这条记忆", 404)


__all__ = ["AgentService", "AgentError", "MAX_MESSAGE"]
