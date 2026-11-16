"""P2d：Agent 会话、消息与偏好记忆。"""
from __future__ import annotations

from conftest import hdr, make_owner


def test_chat_creates_conversation_and_replies(open_reg):
    token = make_owner(open_reg, name="boss")
    r = open_reg.client.post("/v2/agent/chat", headers=hdr(token), json={"message": "怎么批量提取一个博主？"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["conversation_id"].startswith("c_")
    assert "creators/jobs" in body["reply"]

    conv = open_reg.client.get(f"/v2/agent/conversations/{body['conversation_id']}", headers=hdr(token)).json()
    assert [m["role"] for m in conv["messages"]] == ["user", "assistant"]

    # 继续同一会话
    r2 = open_reg.client.post("/v2/agent/chat", headers=hdr(token),
                              json={"message": "库里有多少", "conversation_id": body["conversation_id"]})
    assert r2.status_code == 200
    conv2 = open_reg.client.get(f"/v2/agent/conversations/{body['conversation_id']}", headers=hdr(token)).json()
    assert len(conv2["messages"]) == 4
    assert "0 条媒体" in conv2["messages"][-1]["content"]


def test_conversation_crud(open_reg):
    token = make_owner(open_reg, name="boss")
    created = open_reg.client.post("/v2/agent/conversations", headers=hdr(token), json={"title": "测试"})
    assert created.status_code == 201
    cid = created.json()["id"]
    assert [c["id"] for c in open_reg.client.get("/v2/agent/conversations", headers=hdr(token)).json()["conversations"]] == [cid]
    assert open_reg.client.delete(f"/v2/agent/conversations/{cid}", headers=hdr(token)).status_code == 204
    assert open_reg.client.get(f"/v2/agent/conversations/{cid}", headers=hdr(token)).status_code == 404


def test_memories(open_reg):
    token = make_owner(open_reg, name="boss")
    assert open_reg.client.put("/v2/memories/default_platform", headers=hdr(token),
                               json={"value": "bilibili"}).status_code == 200
    open_reg.client.put("/v2/memories/default_platform", headers=hdr(token), json={"value": "douyin"})
    mem = open_reg.client.get("/v2/memories", headers=hdr(token)).json()["memories"]
    assert len(mem) == 1 and mem[0]["value"] == "douyin"
    assert open_reg.client.delete("/v2/memories/default_platform", headers=hdr(token)).status_code == 204
    assert open_reg.client.delete("/v2/memories/default_platform", headers=hdr(token)).status_code == 404


def test_agent_is_isolated(open_reg):
    ta = make_owner(open_reg, name="owner_a")
    tb = make_owner(open_reg, name="owner_b")
    cid = open_reg.client.post("/v2/agent/conversations", headers=hdr(ta), json={"title": "私密"}).json()["id"]
    assert open_reg.client.get(f"/v2/agent/conversations/{cid}", headers=hdr(tb)).status_code == 404
    assert open_reg.client.post("/v2/agent/chat", headers=hdr(tb),
                                json={"message": "hi", "conversation_id": cid}).status_code == 404
    open_reg.client.put("/v2/memories/k", headers=hdr(ta), json={"value": "a"})
    assert open_reg.client.get("/v2/memories", headers=hdr(tb)).json()["memories"] == []


def test_empty_message_rejected(open_reg):
    token = make_owner(open_reg, name="boss")
    assert open_reg.client.post("/v2/agent/chat", headers=hdr(token), json={"message": ""}).status_code == 422
