"""真实 Chromium × 真实服务端 × 构建产物（同一 https 来源，/getmedia/ 前缀）。"""
import json
import re

import pytest
from playwright.sync_api import sync_playwright

PW = "correct horse 1"


@pytest.fixture(scope="module")
def browser():
    with sync_playwright() as p:
        b = p.chromium.launch()
        yield b
        b.close()


def new_page(browser, width=1000):
    ctx = browser.new_context(ignore_https_errors=True, viewport={"width": width, "height": 800})
    ctx.set_default_timeout(6000)
    page = ctx.new_page()
    page.reqs, page.console = [], []
    page.on("request", lambda r: page.reqs.append(r.url))
    page.on("console", lambda m: page.console.append((m.type, m.text)))
    return ctx, page


def register(page, site, name, password=PW):
    page.goto(site.base + "register")
    page.get_by_label("登录名").fill(name)
    page.get_by_label("密码", exact=True).fill(password)
    page.get_by_label("再次输入密码").fill(password)
    page.get_by_label("我已年满 18 周岁").check()
    page.get_by_role("button", name="注册").click()
    page.get_by_test_id("recovery-code").wait_for()
    return page.get_by_test_id("recovery-code").inner_text().strip()


def ack_recovery(page):
    page.get_by_label("我已把恢复码保存在安全的地方").check()
    page.get_by_role("button", name="继续").click()
    page.get_by_role("heading", name="账号与安全").wait_for()


def login(page, site, name, pw):
    page.goto(site.base + "login")
    page.get_by_label("登录名").fill(name)
    page.get_by_label("密码", exact=True).fill(pw)
    page.get_by_role("button", name=re.compile("登录")).click()


def test_home_download_no_third_party_no_storage(browser, site):
    ctx, page = new_page(browser)
    page.goto(site.base)
    page.get_by_test_id("apk-link").wait_for()
    assert page.get_by_test_id("apk-link").get_attribute("href") == "https://example.invalid/getmedia.apk"
    assert "ab" * 32 in page.content()
    assert page.locator('meta[name="robots"]').get_attribute("content") == "noindex,nofollow"
    origin = site.base.split("/getmedia")[0]
    assert all(u.startswith(origin) for u in page.reqs), [u for u in page.reqs if not u.startswith(origin)]
    assert not [m for m in page.console if "Content Security" in m[1] or "Refused" in m[1]], page.console
    assert page.evaluate("sessionStorage.length") == 0
    ctrl = " ".join(page.locator("a, button").all_inner_texts())
    assert "提取" not in ctrl and "播放" not in ctrl
    ctx.close()


def test_register_cookie_attrs_and_account_flow(browser, site):
    ctx, page = new_page(browser)
    code = register(page, site, "alice")
    assert re.fullmatch(r"[A-Z2-7]{4}([- ][A-Z2-7]{4}){3}", code), code
    ck = [c for c in ctx.cookies() if c["name"] == "__Secure-gm_session"]
    assert len(ck) == 1
    c = ck[0]
    assert c["httpOnly"] and c["secure"] and c["sameSite"] == "Strict" and c["path"] == "/getmedia/api/"
    assert "gm_session" not in page.evaluate("document.cookie")
    store = page.evaluate("JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)])")
    assert all(k.startswith("getmedia:") for k, _ in json.loads(store)[0]) and json.loads(store)[1] == []
    assert code.replace("-", "").replace(" ", "") not in store.replace("-", "").replace(" ", "")  # 恢复码不进存储
    ack_recovery(page)
    assert page.get_by_test_id("login-name").inner_text() == "alice"

    page.get_by_label("当前密码").fill(PW)
    page.get_by_label("新密码", exact=True).fill("another pass 22")
    page.get_by_label("再次输入新密码").fill("another pass 22")
    page.get_by_role("button", name="修改密码").click()
    page.get_by_text("密码已修改").wait_for()
    page.get_by_role("button", name="退出当前设备").click()
    page.get_by_role("link", name="登录").first.wait_for()
    login(page, site, "alice", PW)
    page.get_by_text("登录名或密码不正确").wait_for()
    page.get_by_label("密码", exact=True).fill("another pass 22")
    page.get_by_role("button", name=re.compile("登录")).click()
    page.get_by_role("heading", name="账号与安全").wait_for()
    ctx.close()


def test_csrf_and_origin_enforced(browser, site):
    ctx, page = new_page(browser)
    register(page, site, "carol")
    ack_recovery(page)
    api = site.base + "api"
    origin = site.base.split("/getmedia")[0]
    r = ctx.request.post(api + "/account/logout", headers={"Origin": origin})
    assert r.status == 403 and r.json()["code"] == "csrf"
    r = ctx.request.post(api + "/account/logout", headers={"X-GetMedia-CSRF": "1", "Origin": "https://evil.example"})
    assert r.status == 403
    assert ctx.request.get(api + "/account/me").status == 200
    ctx.close()


def test_recover_flow(browser, site):
    ctx, page = new_page(browser)
    code = register(page, site, "dave")
    ack_recovery(page)
    page.get_by_role("button", name="退出当前设备").click()
    page.get_by_role("link", name="登录").first.wait_for()
    page.goto(site.base + "recover")
    page.get_by_label("登录名").fill("dave")
    page.get_by_label("恢复码").fill(code)
    page.get_by_label("新密码", exact=True).fill("brand new pass 3")
    page.get_by_label("再次输入新密码").fill("brand new pass 3")
    page.get_by_role("button", name="重置密码").click()
    new_code = page.get_by_test_id("recovery-code").inner_text().strip()
    assert new_code != code
    page.get_by_role("button", name="我已保存，去登录").click()
    login(page, site, "dave", "brand new pass 3")
    page.get_by_role("heading", name=re.compile("恢复码|账号与安全")).wait_for()
    ctx.close()


def test_feedback_with_image_and_legal_pages(browser, site, tmp_path):
    from PIL import Image
    ctx, page = new_page(browser)
    register(page, site, "erin")
    ack_recovery(page)
    img = tmp_path / "p.png"
    Image.new("RGB", (20, 20), (1, 2, 3)).save(img)
    page.goto(site.base + "contact")
    page.get_by_label("内容").fill("网页端反馈测试")
    page.locator("#fb-img").set_input_files(str(img))
    page.get_by_role("button", name="提交").click()
    page.get_by_test_id("feedback-id").wait_for()
    with site.app.state.engine.begin() as c:
        row = c.exec_driver_sql("select image_paths,diagnostics_json from feedback where text like '网页端%'").fetchone()
    assert row and len(json.loads(row[0])) == 1 and json.loads(row[1])["channel"] == "web"
    page.goto(site.base + "legal/privacy-policy")
    page.locator(".md table, table").first.wait_for()
    assert page.locator("script").count() == 1  # 只有构建出的入口脚本
    ctx.close()


def test_mobile_360_no_horizontal_scroll(browser, site):
    ctx, page = new_page(browser, width=360)
    for p in ("", "register", "login", "recover", "legal/user-agreement", "legal/privacy-policy", "contact", "nope"):
        page.goto(site.base + p)
        page.wait_for_load_state("networkidle")
        assert page.evaluate("document.documentElement.scrollWidth") <= 360, p
    ctx.close()


def test_delete_account(browser, site):
    ctx, page = new_page(browser)
    register(page, site, "frank")
    ack_recovery(page)
    page.get_by_text("注销账号").first.click()
    page.get_by_label("输入密码确认").fill(PW)
    page.get_by_label("我了解注销后数据将被永久删除").check()
    page.get_by_role("button", name="注销账号").click()
    page.get_by_text("注销申请已提交").wait_for()
    login(page, site, "frank", PW)
    page.get_by_text("登录名或密码不正确").wait_for()
    ctx.close()
