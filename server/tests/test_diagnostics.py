"""P2f：提取失败上报。"""
from __future__ import annotations

from conftest import hdr, make_owner


def report(env, token, **body):
    payload = {"platform": "bilibili", "video_id": "BV1xx411c7mD", "stage": "extract",
               "error_code": "http_412", "detail": "平台要求验证", "client_version": "1.0.0"}
    payload.update(body)
    return env.client.post("/v2/diagnostics/extractions", headers=hdr(token), json=payload)


def test_report_and_list(open_reg):
    token = make_owner(open_reg, name="boss")
    r = report(open_reg, token)
    assert r.status_code == 201, r.text
    rid = r.json()["id"]
    assert r.json()["error_code"] == "http_412"

    listed = open_reg.client.get("/v2/diagnostics/extractions", headers=hdr(token)).json()["reports"]
    assert [x["id"] for x in listed] == [rid]
    assert open_reg.client.get(f"/v2/diagnostics/extractions/{rid}", headers=hdr(token)).json()["platform"] == "bilibili"

    report(open_reg, token, platform="douyin", error_code="captcha")
    summary = open_reg.client.get("/v2/diagnostics/summary?days=7", headers=hdr(token)).json()["groups"]
    assert {(g["platform"], g["error_code"], g["n"]) for g in summary} == {("bilibili", "http_412", 1),
                                                                          ("douyin", "captcha", 1)}


def test_report_is_isolated(open_reg):
    ta = make_owner(open_reg, name="owner_a")
    tb = make_owner(open_reg, name="owner_b")
    rid = report(open_reg, ta).json()["id"]
    assert open_reg.client.get(f"/v2/diagnostics/extractions/{rid}", headers=hdr(tb)).status_code == 404
    assert open_reg.client.get("/v2/diagnostics/extractions", headers=hdr(tb)).json()["reports"] == []


def test_report_validation(open_reg):
    token = make_owner(open_reg, name="boss")
    assert report(open_reg, token, detail="x" * 2001).status_code == 422
    assert open_reg.client.get("/v2/diagnostics/extractions/999", headers=hdr(token)).status_code == 404
