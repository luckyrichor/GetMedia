"""提取 Agent 的模型客户端（P2h，方案 B）。

只走 OpenAI 兼容的 ``POST {base_url}/chat/completions``（火山方舟 Ark 与大多数兼容网关都是这个形状），
不引入厂商 SDK。三条硬约束：

1. **密钥只在服务器 env 里**（``/etc/getmedia/llm.env``），不落库、不进日志、不回显给客户端；
2. **不记录 prompt 与响应原文**：日志只有模型名与异常类型（旧项目踩过"日志里带用户内容"的坑）；
3. **没有密钥就完全不发外网请求**：返回 ``None``，由调用方落回确定性规则，并在 meta 里写清原因。

失败策略是"能降级就降级"：模型超时/限流/返回空 → 换下一个模型；全部失败 → 规则应答。
用户看到的是"这次没走模型"，而不是 500。
"""
from __future__ import annotations

import json
import logging
import re
from datetime import datetime, timezone
from typing import Callable

import httpx
from sqlalchemy import text
from sqlalchemy.engine import Engine

from .agent import AgentError
from .config import Settings

log = logging.getLogger("getmedia.agent.model")

# 用户可能把凭据粘进聊天框。命中就不发出去，也不落进 messages 表。
_SECRET = re.compile(
    r"(?i)(bearer\s+[a-z0-9._\-]{8,}"
    r"|api[_-]?key\s*[:=]"
    r"|cookie\s*[:=]"
    r"|sessdata\s*="
    r"|bili_jct\s*="
    r"|authorization\s*:)"
)

_SYSTEM = """你是 GetMedia 的提取助手。GetMedia 是自用的媒体归档服务：手机端负责提取，服务端只记账。
要求：
- 用简体中文，直给结论与下一步，不超过 6 句；
- 只讲你确实知道的接口（见下方能力表），不要编造参数；
- 不索要、不复述任何 Cookie、令牌、密钥；
- 涉及平台风控（403/412）时说明"需要先在该平台登录授权"，不要建议绕过；
- 不确定就说"我不确定"，并给出可验证的下一步。

能力表（内部路径，对外由 /getmedia/api 或 /getmedia/owner/api 转发）：
- 媒体库：GET /v2/tracks、GET /v2/media/{id}（Range 流）、GET /v2/extraction-jobs
- 提取：POST /v2/extract
- 博主批量：POST /v2/creators/preview、POST /v2/creators/jobs（mode=window|all|date_range|latest_n）、
  GET /v2/creators/jobs/{id}/items、POST /v2/creators/jobs/{id}/items:import（抖音走这条）
- 定时：GET/POST /v2/schedules、POST /v2/schedules/{id}/run-once
- 授权：POST /account/platform-auth/start|cookie|complete|status、GET /account/platform-bindings
- 上报：POST /v2/diagnostics/extractions
"""


def today(now: int) -> str:
    return datetime.fromtimestamp(now, tz=timezone.utc).strftime("%Y-%m-%d")


def guard_secrets(message: str) -> str:
    """消息里出现疑似凭据 → 直接拒绝（既不发给模型，也不写库）。"""
    if _SECRET.search(message or ""):
        raise AgentError("secret_in_message",
                         "消息里似乎包含 Cookie、令牌或密钥。请先移除它们；凭据请走平台授权接口。", 400)
    return message


class ModelBrain:
    """可调用的模型大脑：``(user_id, message, context) -> (reply | None, meta)``。"""

    def __init__(self, settings: Settings, engine: Engine, clock: Callable[[], float], *,
                 client_factory: Callable[[], httpx.Client] | None = None):
        self.s, self.engine, self.clock = settings, engine, clock
        self._factory = client_factory or (lambda: httpx.Client(timeout=settings.llm_timeout))

    # ---------- 可用性 ----------
    @property
    def ready(self) -> bool:
        return bool(self.s.llm_api_key and self.s.llm_models)

    def models(self) -> list[str]:
        return list(self.s.llm_models)

    def calls_today(self, user_id: str) -> int:
        with self.engine.begin() as c:
            n = c.execute(text("SELECT calls FROM agent_usage WHERE user_id=:u AND day=:d"),
                          {"u": user_id, "d": today(int(self.clock()))}).scalar()
        return int(n or 0)

    def _take_quota(self, user_id: str) -> bool:
        """先占额度再调用：失败也计数，避免"一直失败一直重试"把配额当免费。"""
        cap = self.s.llm_daily_cap
        if cap <= 0:
            return False
        d = today(int(self.clock()))
        with self.engine.begin() as c:
            n = c.execute(text("SELECT calls FROM agent_usage WHERE user_id=:u AND day=:d"),
                          {"u": user_id, "d": d}).scalar()
            if int(n or 0) >= cap:
                return False
            c.execute(text("INSERT INTO agent_usage(user_id, day, calls) VALUES(:u,:d,1)"
                           " ON CONFLICT(user_id,day) DO UPDATE SET calls=calls+1"),
                      {"u": user_id, "d": d})
        return True

    # ---------- 调用 ----------
    def __call__(self, user_id: str, message: str, context: dict) -> tuple[str | None, dict]:
        guard_secrets(message)
        if not self.ready:
            return None, {"engine": "rules", "reason": "llm_unconfigured"}
        if not self._take_quota(user_id):
            return None, {"engine": "rules", "reason": "daily_cap"}

        payload_messages = [
            {"role": "system", "content": _SYSTEM + self._context_block(context)},
            {"role": "user", "content": message},
        ]
        url = self.s.llm_base_url.rstrip("/") + "/chat/completions"
        headers = {"Authorization": f"Bearer {self.s.llm_api_key}", "Content-Type": "application/json"}
        last_error = ""
        for model in self.s.llm_models:
            body = {"model": model, "messages": payload_messages,
                    "max_tokens": self.s.llm_max_tokens, "temperature": 0.3}
            try:
                reply = self._post(url, headers, body)
            except Exception as exc:  # noqa: BLE001 - 任何异常都换下一个模型，不抛给用户
                last_error = type(exc).__name__
                log.warning("模型调用失败 model=%s error=%s", model, last_error)
                continue
            if reply:
                return reply, {"engine": "model", "model": model}
            last_error = "empty_reply"
            log.warning("模型返回空 model=%s", model)
        return None, {"engine": "rules", "reason": last_error or "model_unavailable"}

    def _post(self, url: str, headers: dict, body: dict) -> str | None:
        with self._factory() as client:
            r = client.post(url, headers=headers, json=body)
        if r.status_code != 200:
            # 只记状态码，不记响应体：有的网关会把请求原文回显在错误里
            raise RuntimeError(f"http_{r.status_code}")
        data = r.json()
        choices = data.get("choices") or []
        if not choices:
            return None
        text_ = (choices[0].get("message") or {}).get("content") or ""
        text_ = text_.strip()
        return text_[:4000] or None

    def _context_block(self, context: dict) -> str:
        stats = context.get("stats") or {}
        mem = context.get("memories") or {}
        lines = ["", "当前事实（以此为准，不要推算）：",
                 f"- 媒体条数：{stats.get('tracks', 0)}",
                 f"- 归档占用：{stats.get('archive_bytes', 0)} 字节；缓存占用：{stats.get('cache_bytes', 0)} 字节"]
        if mem:
            lines.append("- 用户偏好记忆：" + "；".join(f"{k}={v}" for k, v in list(mem.items())[:10]))
        return "\n".join(lines)


__all__ = ["ModelBrain", "guard_secrets", "today"]
