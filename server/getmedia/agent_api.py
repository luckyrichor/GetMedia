"""提取 Agent 的 HTTP 层（P2d）。"""
from __future__ import annotations

from fastapi import Depends, Response
from pydantic import BaseModel, ConfigDict, Field

from .agent import MAX_MESSAGE, AgentError, AgentService
from .errors import ApiError
from .service import AuthCtx


class ConversationBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    title: str = Field("", max_length=80)


class ChatBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    message: str = Field(min_length=1, max_length=MAX_MESSAGE)
    conversation_id: str | None = Field(None, max_length=40)


class MemoryBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    value: str = Field(max_length=2000)


def register(app, guard, engine, clock, *, media=None, creator_jobs=None, brain=None) -> AgentService:
    service = AgentService(engine, app.state.settings, clock, brain=brain, media=media,
                           creator_jobs=creator_jobs)
    app.state.agent = service

    def _err(exc: AgentError) -> None:
        raise ApiError(exc.status, exc.code, exc.detail) from exc

    @app.get("/v2/agent/conversations")
    def list_conversations(ctx: AuthCtx = Depends(guard)):
        return {"conversations": service.list_conversations(ctx.user_id)}

    @app.post("/v2/agent/conversations", status_code=201)
    def create_conversation(body: ConversationBody, ctx: AuthCtx = Depends(guard)):
        return service.create_conversation(ctx.user_id, body.title)

    @app.get("/v2/agent/conversations/{conversation_id}")
    def get_conversation(conversation_id: str, ctx: AuthCtx = Depends(guard)):
        try:
            return service.get_conversation(ctx.user_id, conversation_id)
        except AgentError as exc:
            _err(exc)

    @app.delete("/v2/agent/conversations/{conversation_id}", status_code=204)
    def delete_conversation(conversation_id: str, ctx: AuthCtx = Depends(guard)):
        try:
            service.delete_conversation(ctx.user_id, conversation_id)
        except AgentError as exc:
            _err(exc)
        return Response(status_code=204)

    @app.post("/v2/agent/chat")
    def chat(body: ChatBody, ctx: AuthCtx = Depends(guard)):
        try:
            return service.chat(ctx.user_id, body.message, conversation_id=body.conversation_id)
        except AgentError as exc:
            _err(exc)

    @app.get("/v2/memories")
    def list_memories(ctx: AuthCtx = Depends(guard)):
        return {"memories": service.list_memories(ctx.user_id)}

    @app.put("/v2/memories/{key}")
    def put_memory(key: str, body: MemoryBody, ctx: AuthCtx = Depends(guard)):
        try:
            return service.put_memory(ctx.user_id, key, body.value)
        except AgentError as exc:
            _err(exc)

    @app.delete("/v2/memories/{key}", status_code=204)
    def delete_memory(key: str, ctx: AuthCtx = Depends(guard)):
        try:
            service.delete_memory(ctx.user_id, key)
        except AgentError as exc:
            _err(exc)
        return Response(status_code=204)

    return service


__all__ = ["register", "ConversationBody", "ChatBody", "MemoryBody"]
