"""提取 Agent（P2d/P2h）：会话、消息、偏好记忆、动作闭环。

模型是可插拔的：``brain`` 未注入（或没有密钥）时用确定性规则应答——不假装是模型，
也不在没有密钥的情况下把请求发到外网。

动作（actions）由**服务端**生成，不由模型自由发挥：用户在消息里贴一个可解析的作品链接，
服务端就建一条 ``pending`` 动作；手机 ``claim`` 后执行，再把结果 ``result`` 回来。
模型只负责"说"，不负责"做"——这样模型换掉、模型挂了，动作链路都不受影响。
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
MAX_DIAGNOSTIC = 2000
ACTION_MODES = ("phone", "server")


class AgentError(Exception):
    def __init__(self, code: str, detail: str, status: int = 400):
        super().__init__(detail)
        self.code, self.detail, self.status = code, detail, status


def _rules_brain(user_id: str, message: str, context: dict) -> tuple[str, dict]:
    """没有模型时的确定性应答：只讲事实与下一步能做什麼，不编造。"""
    stats = context.get("stats", {})
    if any(k in message for k in ("批量", "博主", "主页", "UP")):
        return ("批量提取要先建任务：POST /v2/creators/jobs，mode 可选 all / date_range / latest_n，"
                "置顶作品会带 is_pinned 但不插队。任务建好后手机按 /items?state=pending 逐条领取，"
                "完成后回传 /items/{video_id}/state。"), {"engine": "rules"}
    if any(k in message for k in ("定时", "每天", "定期")):
        return ("定时用 /v2/schedules：name + input + mode + interval_minutes；触发时只是建一个批量任务，"
                "下载进度仍由任务本身记录。可用 /v2/schedules/{id}/run-once 立即跑一次。"), {"engine": "rules"}
    if any(k in message for k in ("多少", "统计", "库", "空间")):
        return (f"当前库里：{stats.get('tracks', 0)} 条媒体，"
                f"其中存储 {stats.get('archive_bytes', 0)} 字节、缓存 {stats.get('cache_bytes', 0)} 字节。"
                "详细配额见 /v2/maintenance/storage。"), {"engine": "rules"}
    if any(k in message for k in ("授权", "cookie", "登录")):
        return ("平台授权在 /account/platform-auth/start 发起，支持网页登录取 Cookie 与导入 Cookie 两种方式；"
                "Cookie 用 AES-256-GCM 加密保存，服务端不回显明文。"), {"engine": "rules"}
    if any(k in message for k in ("失败", "报错", "error")):
        return ("提取失败请把平台、作品号、阶段与错误码回传 /v2/diagnostics/extractions，"
                "我会按平台与错误码归类，方便定位是风控、授权过期还是解析规则变了。"), {"engine": "rules"}
    return (f"我可以帮你：批量列出博主作品、建定时任务、查媒体库与配额、查看授权状态。"
            f"当前库里有 {stats.get('tracks', 0)} 条媒体。直接说你要做什么即可。"), {"engine": "rules"}


def _looks_like_media(url: str, platform: str, message: str) -> bool:
    """只对"单个作品"建动作；空间/主页链接应走批量任务，不在这里假装能提取。"""
    from .creator_links import BV_RE

    low = url.lower()
    if platform == "bilibili":
        return bool(BV_RE.search(message)) or "/video/" in low or "b23.tv" in low or "bili2233" in low
    if platform == "douyin":
        return any(p in low for p in ("/video/", "/note/", "/share/video/")) or "v.douyin.com" in low
    return False


class AgentService:
    def __init__(self, engine: Engine, settings: Settings, clock, *, brain: Callable | None = None,
                 media=None, creator_jobs=None, model=None):
        self.engine, self.s, self.clock = engine, settings, clock
        self.brain = brain or _rules_brain
        self.media, self.creator_jobs = media, creator_jobs
        # model 与 brain 通常是同一个对象；分开存是为了 status 能问"今天用了几次"
        self.model = model

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
        conv["actions"] = self.list_actions(user_id, cid)
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
        # 凭据不进库、不发外网：命中就 400（模型与规则都不该看到 Cookie）
        from .agent_model import guard_secrets

        guard_secrets(message)
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
        reply, meta = "", {"engine": "rules"}
        try:
            out = self.brain(user_id, message, context)
            if isinstance(out, tuple):
                reply, extra = out
                meta = {**meta, **(extra or {})}
            else:                       # 兼容只返回字符串的 brain
                reply = out
            if not reply:
                reply, meta = _rules_brain(user_id, message, context)[0], {"engine": "rules", "reason": "empty_reply"}
        except AgentError:
            raise
        except Exception as exc:  # noqa: BLE001 - 模型/规则出错不能吞掉用户消息
            log.warning("agent brain 失败：%s", type(exc).__name__)
            reply, meta = "我这边处理出了点问题，请稍后再试；你的消息已经记录在会话里。", {"engine": "rules", "reason": "brain_error"}

        action = self._maybe_action(user_id, cid, message)
        if action:
            meta = {**meta, "action_id": action["id"]}
        with self.engine.begin() as c:
            c.execute(text("INSERT INTO agent_messages(conversation_id, role, content, meta_json, created_at)"
                           " VALUES(:c,'assistant',:t,:m,:n)"),
                      {"c": cid, "t": reply[:MAX_MESSAGE], "m": json.dumps(meta, ensure_ascii=False),
                       "n": self.now()})
            c.execute(text("UPDATE agent_conversations SET updated_at=:n WHERE id=:c"), {"n": self.now(), "c": cid})
        return {"conversation_id": cid, "reply": reply, "created_at": now,
                "engine": meta.get("engine", "rules"), "model": meta.get("model"),
                "reason": meta.get("reason"), "action": action}

    def _maybe_action(self, user_id: str, cid: str, message: str) -> dict | None:
        """消息里有一个可解析的作品链接 → 建一条待领取动作（不进模型，纯规则判定）。"""
        from .creator_links import find_urls, platform_of

        for url in find_urls(message):
            platform = platform_of(url)
            if platform is None or not _looks_like_media(url, platform.value, message):
                continue
            return self.create_action(user_id, cid, url, platform.value)
        return None

    def _context(self, user_id: str) -> dict:
        stats = {"tracks": 0, "archive_bytes": 0, "cache_bytes": 0}
        if self.media is not None:
            used = self.media.stats()
            stats = {"tracks": sum(v["tracks"] for v in used.values()),
                     "archive_bytes": used.get("archive", {}).get("bytes", 0),
                     "cache_bytes": used.get("cache", {}).get("bytes", 0)}
        with self.engine.begin() as c:
            rows = c.execute(text("SELECT key, value FROM agent_memories WHERE user_id=:u"
                                  " ORDER BY updated_at DESC LIMIT 20"), {"u": user_id}).all()
        return {"stats": stats, "memories": {r[0]: r[1] for r in rows}, "now": self.now()}

    # ---------- 状态的对外口径 ----------
    def status(self, user_id: str) -> dict:
        brain = self.model
        ready = bool(getattr(brain, "ready", False))
        calls = brain.calls_today(user_id) if hasattr(brain, "calls_today") else 0
        return {"engine": "model" if ready else "rules",
                "models": list(getattr(brain, "models", lambda: [])()) if ready else [],
                "calls_today": calls, "daily_cap": int(self.s.llm_daily_cap),
                "vault_ready": bool(self.s.credential_key)}

    # ---------- 动作闭环 ----------
    def create_action(self, user_id: str, cid: str, url: str, platform: str, kind: str = "extract") -> dict:
        aid, now = "a_" + uuid.uuid4().hex[:20], self.now()
        with self.engine.begin() as c:
            c.execute(text("INSERT INTO agent_actions(id, user_id, conversation_id, kind, platform, input_url,"
                           " status, created_at) VALUES(:i,:u,:c,:k,:p,:url,'pending',:n)"),
                      {"i": aid, "u": user_id, "c": cid, "k": kind, "p": platform, "url": url[:2048], "n": now})
        return self.action(user_id, aid)

    def action(self, user_id: str, aid: str) -> dict:
        with self.engine.begin() as c:
            row = c.execute(text("SELECT id, conversation_id, kind, platform, input_url, status, mode,"
                                 " result_json, created_at, claimed_at, finished_at FROM agent_actions"
                                 " WHERE id=:i AND user_id=:u"), {"i": aid, "u": user_id}).first()
        if row is None:
            raise AgentError("action_not_found", "没有这个动作", 404)
        return self._action_out(dict(row._mapping))

    @staticmethod
    def _action_out(row: dict) -> dict:
        out = {k: row[k] for k in ("id", "conversation_id", "kind", "platform", "input_url",
                                   "status", "mode", "created_at", "claimed_at", "finished_at")}
        try:
            out["result"] = json.loads(row["result_json"]) if row.get("result_json") else None
        except ValueError:
            out["result"] = None
        return out

    def list_actions(self, user_id: str, conversation_id: str | None = None, limit: int = 50) -> list[dict]:
        sql = ("SELECT id, conversation_id, kind, platform, input_url, status, mode, result_json,"
               " created_at, claimed_at, finished_at FROM agent_actions WHERE user_id=:u")
        params = {"u": user_id, "l": limit}
        if conversation_id:
            sql += " AND conversation_id=:c"
            params["c"] = conversation_id
        sql += " ORDER BY id DESC LIMIT :l"
        with self.engine.begin() as c:
            rows = c.execute(text(sql), params).all()
        return [self._action_out(dict(r._mapping)) for r in rows]

    def claim_action(self, user_id: str, aid: str, mode: str) -> dict:
        if mode not in ACTION_MODES:
            raise AgentError("bad_mode", "mode 只能是 phone 或 server")
        self.action(user_id, aid)                      # 先做归属校验（404 优先于 409）
        with self.engine.begin() as c:
            changed = c.execute(text("UPDATE agent_actions SET status='running', mode=:m, claimed_at=:n"
                                     " WHERE id=:i AND user_id=:u AND status='pending'"),
                                {"i": aid, "u": user_id, "m": mode, "n": self.now()}).rowcount
        if not changed:
            raise AgentError("action_not_pending", "这个动作已经领取过；要重新提取请再发一次消息", 409)
        return self.action(user_id, aid)

    def result_action(self, user_id: str, aid: str, *, success: bool, track_id: int | None = None,
                      title: str | None = None, diagnostic: str = "") -> dict:
        row = self.action(user_id, aid)
        diagnostic = (diagnostic or "")[:MAX_DIAGNOSTIC]
        if success and track_id is None:
            raise AgentError("missing_track", "成功的结果必须带上媒体记录号")
        if row["status"] == "pending":
            raise AgentError("action_not_claimed", "请先领取这个动作再回报结果", 409)
        payload = {"success": bool(success), "track_id": track_id,
                   "title": (title or "")[:200], "diagnostic": diagnostic, "mode": row["mode"]}
        serialized = json.dumps(payload, ensure_ascii=False, sort_keys=True)
        if row["result"] is not None:
            if json.dumps(row["result"], ensure_ascii=False, sort_keys=True) != serialized:
                raise AgentError("result_frozen", "结果已保存，不能覆盖", 409)
            return row
        status = "done" if success else "failed"
        with self.engine.begin() as c:
            changed = c.execute(text("UPDATE agent_actions SET status=:s, result_json=:r, finished_at=:n"
                                     " WHERE id=:i AND user_id=:u AND status='running'"),
                                {"s": status, "r": serialized, "n": self.now(), "i": aid, "u": user_id}).rowcount
        if not changed:
            raise AgentError("action_not_running", "动作状态已变化，请刷新", 409)
        return self.action(user_id, aid)


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


__all__ = ["AgentService", "AgentError", "MAX_MESSAGE", "MAX_DIAGNOSTIC", "ACTION_MODES"]
