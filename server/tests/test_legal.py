import shutil

import pytest

from getmedia.api import create_app
from getmedia.legal import LegalError, parse
from conftest import hdr, login, register


def _file(env, name="user-agreement.md"):
    return env.legal_dir / name


def _bump(env, name, version=None, extra=""):
    p = _file(env, name)
    txt = p.read_text(encoding="utf-8")
    if version:
        import re
        txt = re.sub(r"版本：v\d+", f"版本：v{version}", txt, count=1)
    p.write_text(txt + extra, encoding="utf-8")


def test_legal_endpoint_shape(env):
    d = env.client.get("/v2/legal").json()["documents"]
    assert set(d) == {"user_agreement", "privacy_policy"}
    for v in d.values():
        assert v["version"] == 1 and v["effective_date"] == "2026-10-30" and len(v["sha256"]) == 64 and "GetMedia" in v["text"]


def test_header_parse_failure_refuses_start(env):
    _file(env).write_text("没有版本头\n正文", encoding="utf-8")
    with pytest.raises(LegalError):
        create_app(env.settings, clock=env.clock, alerts=env.alerts)


def test_missing_file_refuses_start(env):
    _file(env, "privacy-policy.md").unlink()
    with pytest.raises(LegalError):
        create_app(env.settings, clock=env.clock, alerts=env.alerts)


def test_same_version_changed_text_is_rejected_and_alerted(open_reg):
    env = open_reg
    before = env.client.get("/v2/legal").json()["documents"]["user_agreement"]
    _bump(env, "user-agreement.md", extra="\n偷偷加的一句\n")
    after = env.client.get("/v2/legal").json()["documents"]["user_agreement"]
    assert after == before  # 继续提供已登记文本
    assert any(k == "legal_hash_mismatch" for k, _ in env.alerts.items)
    # 注册仍以旧哈希为准
    assert register(env).status_code == 201


def test_tamper_survives_restart(open_reg):
    env = open_reg
    before = env.client.get("/v2/legal").json()["documents"]["user_agreement"]
    _bump(env, "user-agreement.md", extra="\n篡改\n")
    app2 = create_app(env.settings, clock=env.clock, alerts=env.alerts)
    assert app2.state.legal.public()["user_agreement"]["sha256"] == before["sha256"]
    assert any(k == "legal_hash_mismatch" for k, _ in env.alerts.items)


def test_new_version_takes_effect_and_forces_reconsent(open_reg):
    env = open_reg
    t = register(env).json()["session"]["token"]
    assert env.client.get("/account/me", headers=hdr(t)).json()["consent_required"] is False
    _bump(env, "privacy-policy.md", version=2, extra="\n新增条款\n")
    me = env.client.get("/account/me", headers=hdr(t)).json()
    assert me["consent_required"] is True and me["legal_versions"]["privacy_policy"] == 2
    env.clock.advance(301)
    assert login(env).json()["consent_required"] is True
    # 旧版本哈希注册 → legal_outdated；重新同意后恢复
    d = env.client.get("/v2/legal").json()["documents"]
    new = {k: {"version": d[k]["version"], "sha256": d[k]["sha256"]} for k in d}
    r = env.client.post("/account/consent", headers=hdr(t), json=new)
    assert r.status_code == 204
    assert env.client.get("/account/me", headers=hdr(t)).json()["consent_required"] is False


def test_stale_consent_body_rejected(open_reg):
    env = open_reg
    t = register(env).json()["session"]["token"]
    d = env.client.get("/v2/legal").json()["documents"]
    old = {k: {"version": d[k]["version"], "sha256": d[k]["sha256"]} for k in d}
    _bump(env, "user-agreement.md", version=2, extra="\nv2\n")
    r = env.client.post("/account/consent", headers=hdr(t), json=old)
    assert r.status_code == 409 and r.json()["code"] == "legal_outdated"


def test_version_rollback_rejected(open_reg):
    env = open_reg
    _bump(env, "user-agreement.md", version=2, extra="\nv2\n")
    assert env.client.get("/v2/legal").json()["documents"]["user_agreement"]["version"] == 2
    shutil.copy(env.tmp / ".." / "x", env.tmp / "unused") if False else None
    p = _file(env)
    p.write_text(p.read_text(encoding="utf-8").replace("版本：v2", "版本：v1"), encoding="utf-8")
    assert env.client.get("/v2/legal").json()["documents"]["user_agreement"]["version"] == 2
    assert any(k == "legal_hash_mismatch" for k, _ in env.alerts.items)


def test_hot_reload_parse_failure_keeps_previous(open_reg):
    env = open_reg
    before = env.client.get("/v2/legal").json()["documents"]["privacy_policy"]
    _file(env, "privacy-policy.md").write_text("坏了", encoding="utf-8")
    assert env.client.get("/v2/legal").json()["documents"]["privacy_policy"] == before
    assert any(k == "legal_parse_failed" for k, _ in env.alerts.items)


def test_parse_fullwidth_space_and_invalid_date():
    ok = parse("x", "# t\n\n版本：v3　生效日期：2026 年 1 月 5 日\n正文".encode())
    assert ok.version == 3 and ok.effective_date == "2026-01-05"
    with pytest.raises(LegalError):
        parse("x", "版本：v1　生效日期：2026 年 2 月 31 日".encode())
