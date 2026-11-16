"""P2h：Agent 动作闭环（claim/result）、状态口径与模型降级。"""
from __future__ import annotations

import pytest

from conftest import hdr, make_owner

BV = "https://www.bilibili.com/video/BV1GJ411x7h7"
SPACE = "https://space.bilibili.com/123456"


def _me(env, token):
    return env.client.get("/account/me", headers=hdr(token)).json()


# ---------------------------------------------------------------- 动作生成
def test_media_link_creates_pending_action(open_reg):
    token = make_owner(open_reg, name="boss")
    r = open_reg.client.post("/v2/agent/chat", headers=hdr(token), json={"message": f"帮我提取这个 {BV}"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["engine"] == "rules"
    action = body["action"]
    assert action["status"] == "pending" and action["platform"] == "bilibili"
    assert action["input_url"] == BV and action["mode"] is None

    conv = open_reg.client.get(f"/v2/agent/conversations/{body['conversation_id']}", headers=hdr(token)).json()
    assert [a["id"] for a in conv["actions"]] == [action["id"]]
    assert conv["messages"][1]["meta"]["action_id"] == action["id"]   # 0 是用户消息，1 是助手


def test_profile_link_does_not_create_action(open_reg):
    token = make_owner(open_reg, name="boss")
    body = open_reg.client.post("/v2/agent/chat", headers=hdr(token),
                                json={"message": f"这个博主全都要 {SPACE}"}).json()
    assert body["action"] is None                      # 主页应走批量任务，不假装能单条提取


def test_non_platform_link_does_not_create_action(open_reg):
    token = make_owner(open_reg, name="boss")
    body = open_reg.client.post("/v2/agent/chat", headers=hdr(token),
                                json={"message": "看看这个 https://example.invalid/a.mp4"}).json()
    assert body["action"] is None


# ---------------------------------------------------------------- 领取与回报
def _new_action(env, token) -> str:
    body = env.client.post("/v2/agent/chat", headers=hdr(token), json={"message": f"提取 {BV}"}).json()
    return body["action"]["id"]


def test_claim_then_result_is_idempotent(open_reg):
    token = make_owner(open_reg, name="boss")
    aid = _new_action(open_reg, token)

    claimed = open_reg.client.post(f"/v2/agent/actions/{aid}/claim", headers=hdr(token), json={"mode": "phone"})
    assert claimed.status_code == 200 and claimed.json()["status"] == "running"
    assert claimed.json()["mode"] == "phone" and claimed.json()["claimed_at"]

    # 重复领取必须 409（一次性语义），否则手机重试会重复下载
    again = open_reg.client.post(f"/v2/agent/actions/{aid}/claim", headers=hdr(token), json={"mode": "phone"})
    assert again.status_code == 409 and again.json()["code"] == "action_not_pending"

    done = open_reg.client.post(f"/v2/agent/actions/{aid}/result", headers=hdr(token),
                                json={"success": True, "track_id": 7, "title": "某个作品"})
    assert done.status_code == 200 and done.json()["status"] == "done"
    assert done.json()["result"] == {"success": True, "track_id": 7, "title": "某个作品",
                                     "diagnostic": "", "mode": "phone"}

    # 同样的结果再报一次是幂等的
    same = open_reg.client.post(f"/v2/agent/actions/{aid}/result", headers=hdr(token),
                               json={"success": True, "track_id": 7, "title": "某个作品"})
    assert same.status_code == 200 and same.json()["result"]["track_id"] == 7

    # 换一个结果不能覆盖
    diff = open_reg.client.post(f"/v2/agent/actions/{aid}/result", headers=hdr(token),
                                json={"success": True, "track_id": 8})
    assert diff.status_code == 409 and diff.json()["code"] == "result_frozen"


def test_result_before_claim_is_rejected(open_reg):
    token = make_owner(open_reg, name="boss")
    aid = _new_action(open_reg, token)
    r = open_reg.client.post(f"/v2/agent/actions/{aid}/result", headers=hdr(token),
                             json={"success": True, "track_id": 1})
    assert r.status_code == 409 and r.json()["code"] == "action_not_claimed"


def test_success_result_requires_track_id(open_reg):
    token = make_owner(open_reg, name="boss")
    aid = _new_action(open_reg, token)
    open_reg.client.post(f"/v2/agent/actions/{aid}/claim", headers=hdr(token), json={"mode": "phone"})
    r = open_reg.client.post(f"/v2/agent/actions/{aid}/result", headers=hdr(token), json={"success": True})
    assert r.status_code == 400 and r.json()["code"] == "missing_track"

    failed = open_reg.client.post(f"/v2/agent/actions/{aid}/result", headers=hdr(token),
                                  json={"success": False, "diagnostic": "412 风控"})
    assert failed.status_code == 200 and failed.json()["status"] == "failed"


def test_bad_claim_mode_rejected(open_reg):
    token = make_owner(open_reg, name="boss")
    aid = _new_action(open_reg, token)
    assert open_reg.client.post(f"/v2/agent/actions/{aid}/claim", headers=hdr(token),
                                json={"mode": "cloud"}).status_code == 422


def test_actions_are_isolated_per_user(open_reg):
    ta = make_owner(open_reg, name="owner_a")
    tb = make_owner(open_reg, name="owner_b")
    aid = _new_action(open_reg, ta)
    assert open_reg.client.post(f"/v2/agent/actions/{aid}/claim", headers=hdr(tb),
                                json={"mode": "phone"}).status_code == 404
    assert open_reg.client.post(f"/v2/agent/actions/{aid}/result", headers=hdr(tb),
                                json={"success": False}).status_code == 404
    assert open_reg.client.get("/v2/agent/actions", headers=hdr(tb)).json()["actions"] == []


# ---------------------------------------------------------------- 状态与凭据护栏
def test_status_reports_rules_when_no_model(open_reg):
    token = make_owner(open_reg, name="boss")
    st = open_reg.client.get("/v2/agent/status", headers=hdr(token)).json()
    assert st["engine"] == "rules" and st["models"] == []
    assert st["calls_today"] == 0 and st["daily_cap"] == 200


def test_secret_in_message_is_rejected_and_not_stored(open_reg):
    token = make_owner(open_reg, name="boss")
    r = open_reg.client.post("/v2/agent/chat", headers=hdr(token),
                             json={"message": "SESSDATA=abcd1234; bili_jct=xyz"})
    assert r.status_code == 400 and r.json()["code"] == "secret_in_message"
    assert open_reg.client.get("/v2/agent/conversations", headers=hdr(token)).json()["conversations"] == []


# ---------------------------------------------------------------- 模型客户端（假上游）
class _Resp:
    def __init__(self, code, payload):
        self.status_code, self._p = code, payload

    def json(self):
        return self._p


class _Client:
    def __init__(self, script):
        self.script = list(script)
        self.calls = []

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def post(self, url, headers=None, json=None):
        self.calls.append({"url": url, "model": (json or {}).get("model")})
        return self.script.pop(0)


def test_model_falls_back_to_second_model_and_counts_once(open_reg):
    token = make_owner(open_reg, name="boss")
    uid = _me(open_reg, token)["id"]
    client = _Client([_Resp(500, {}), _Resp(200, {"choices": [{"message": {"content": "  模型回答  "}}]})])
    brain = ModelBrainFactory(open_reg, client)
    reply, meta = brain(uid, "你好", {"stats": {}})
    assert reply == "模型回答" and meta == {"engine": "model", "model": "m2"}
    assert [c["model"] for c in client.calls] == ["m1", "m2"]
    assert client.calls[0]["url"].endswith("/chat/completions")
    assert brain.calls_today(uid) == 1            # 一次对话只记一次，不按模型数计


class ModelBrainFactory:
    """把 _brain 的构造与假上游绑在一起，测试里少写两行。"""

    def __init__(self, env, client):
        from getmedia.agent_model import ModelBrain

        settings = env.settings.with_(llm_api_key="k-test", llm_models=("m1", "m2"))
        self.inner = ModelBrain(settings, env.app.state.engine, env.clock,
                                client_factory=lambda: client)

    def __call__(self, *a):
        return self.inner(*a)

    def calls_today(self, uid):
        return self.inner.calls_today(uid)


def test_model_all_fail_returns_none_with_reason(open_reg):
    token = make_owner(open_reg, name="boss")
    uid = _me(open_reg, token)["id"]
    brain = ModelBrainFactory(open_reg, _Client([_Resp(429, {}), _Resp(200, {"choices": []})]))
    reply, meta = brain(uid, "你好", {"stats": {}})
    assert reply is None and meta["engine"] == "rules" and meta["reason"] == "empty_reply"


def test_daily_cap_stops_calls(open_reg):
    token = make_owner(open_reg, name="boss")
    uid = _me(open_reg, token)["id"]
    client = _Client([_Resp(200, {"choices": [{"message": {"content": "一"}}]})])
    from getmedia.agent_model import ModelBrain

    settings = open_reg.settings.with_(llm_api_key="k", llm_models=("m1",), llm_daily_cap=1)
    brain = ModelBrain(settings, open_reg.app.state.engine, open_reg.clock, client_factory=lambda: client)
    assert brain(uid, "a", {})[1]["engine"] == "model"
    reply, meta = brain(uid, "b", {})
    assert reply is None and meta["reason"] == "daily_cap"
    assert len(client.calls) == 1                  # 第二句根本没发出去
    assert brain.calls_today(uid) == 1


def test_model_reply_goes_through_chat_with_engine_meta(open_reg):
    """接上模型后 /v2/agent/chat 的元数据要如实写明 engine 与 model。"""
    token = make_owner(open_reg, name="boss")
    client = _Client([_Resp(200, {"choices": [{"message": {"content": "用 /v2/creators/jobs 建任务"}}]})])
    from getmedia.agent_model import ModelBrain

    settings = open_reg.settings.with_(llm_api_key="k", llm_models=("m1",))
    open_reg.app.state.agent.brain = ModelBrain(settings, open_reg.app.state.engine, open_reg.clock,
                                                client_factory=lambda: client)
    open_reg.app.state.agent.model = open_reg.app.state.agent.brain
    body = open_reg.client.post("/v2/agent/chat", headers=hdr(token), json={"message": "怎么批量提取"}).json()
    assert body["engine"] == "model" and body["model"] == "m1"
    conv = open_reg.client.get(f"/v2/agent/conversations/{body['conversation_id']}", headers=hdr(token)).json()
    assert conv["messages"][1]["meta"] == {"engine": "model", "model": "m1"}
    st = open_reg.client.get("/v2/agent/status", headers=hdr(token)).json()
    assert st["engine"] == "model" and st["models"] == ["m1"] and st["calls_today"] == 1


@pytest.mark.parametrize("leak", ["Bearer abcdefghijkl", "api_key=sk-123", "Cookie: a=b", "bili_jct=deadbeef"])
def test_guard_secrets_catches_common_shapes(leak):
    from getmedia.agent import AgentError
    from getmedia.agent_model import guard_secrets

    with pytest.raises(AgentError) as ei:
        guard_secrets(f"帮我提取 {leak}")
    assert ei.value.code == "secret_in_message" and ei.value.status == 400


def test_deleting_conversation_removes_its_actions(open_reg):
    token = make_owner(open_reg, name="boss")
    body = open_reg.client.post("/v2/agent/chat", headers=hdr(token), json={"message": f"提取 {BV}"}).json()
    cid, aid = body["conversation_id"], body["action"]["id"]
    assert open_reg.client.delete(f"/v2/agent/conversations/{cid}", headers=hdr(token)).status_code == 204
    assert open_reg.client.get("/v2/agent/actions", headers=hdr(token)).json()["actions"] == []
    assert open_reg.client.post(f"/v2/agent/actions/{aid}/claim", headers=hdr(token),
                                json={"mode": "phone"}).status_code == 404
