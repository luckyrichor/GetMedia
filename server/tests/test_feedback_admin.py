"""反馈收件箱（P5c）：跨账号读、截断身份、状态机与图片取用。"""
import io

import pytest
from PIL import Image
from sqlalchemy import text

from conftest import hdr, make_owner, register


def jpeg(size=(40, 30)):
    im = Image.new("RGB", size, (10, 120, 200))
    buf = io.BytesIO()
    im.save(buf, "JPEG")
    return buf.getvalue()


def submit(env, name, *, text="列表不刷新", key="key-00000001", images=(), **form):
    r = register(env, name=name)
    tok = r.json()["session"]["token"]
    data = {"text": text, "category": "bug", "idempotency_key": key, **form}
    files = [("images", (f"a{i}.jpg", b, "application/octet-stream")) for i, b in enumerate(images)]
    resp = env.client.post("/v2/feedback", data=data, files=files, headers=hdr(tok))
    assert resp.status_code == 201, resp.text
    return resp.json()["id"], tok


def test_inbox_requires_owner(open_reg):
    fid, user_tok = submit(open_reg, "user1")
    # 匿名
    assert open_reg.client.get("/v2/feedback").status_code == 401
    # 普通账号：应用层 403，不因为"知道编号"就能读
    assert open_reg.client.get("/v2/feedback", headers=hdr(user_tok)).status_code == 403
    assert open_reg.client.get(f"/v2/feedback/{fid}", headers=hdr(user_tok)).status_code == 403
    assert open_reg.client.get(f"/v2/feedback/{fid}/images/0", headers=hdr(user_tok)).status_code == 403
    assert open_reg.client.post(f"/v2/feedback/{fid}/status", json={"status": "handled"},
                                headers=hdr(user_tok)).status_code == 403

    owner = make_owner(open_reg)
    assert open_reg.client.get("/v2/feedback", headers=hdr(owner)).status_code == 200


def test_list_hides_identity_and_contact(open_reg):
    fid, _ = submit(open_reg, "reporter", text="播放到 03:24 会卡住",
                    contact="someone@example.invalid")
    owner = make_owner(open_reg)
    body = open_reg.client.get("/v2/feedback", headers=hdr(owner)).json()
    assert body["total"] == 1
    it = body["items"][0]
    assert it["id"] == fid
    # 只有截断编号，没有登录名、没有联系方式明文
    assert len(it["user_ref"]) == 8
    assert "reporter" not in str(body)
    assert "someone@example.invalid" not in str(body)
    # "有没有留联系方式"这个信号保留，正文要能在列表里看见
    assert it["contact"] is True
    assert "03:24" in it["text"]
    assert it["status"] == "new"
    assert body["counts"] == {"new": 1, "handled": 0, "ignored": 0, "all": 1}


def test_detail_and_status(open_reg):
    fid, _ = submit(open_reg, "user1", text="导出没有声音", diagnostics='{"app_version":"0.1.0","channel":"android","os":"Android 14","device":"Pixel 7","android_id":"secret-id"}')
    owner = make_owner(open_reg)
    d = open_reg.client.get(f"/v2/feedback/{fid}", headers=hdr(owner)).json()
    assert d["text"] == "导出没有声音"
    assert d["contact"] == ""
    assert d["diag"]["app_version"] == "0.1.0"
    assert d["diag"]["channel"] == "android"
    # 诊断里的设备唯一标识不进收件箱
    assert "secret-id" not in str(d)

    r = open_reg.client.post(f"/v2/feedback/{fid}/status", json={"status": "handled"}, headers=hdr(owner))
    assert r.status_code == 200 and r.json() == {"id": fid, "status": "handled"}
    # 幂等：重复同一状态仍是 200
    assert open_reg.client.post(f"/v2/feedback/{fid}/status", json={"status": "handled"},
                                headers=hdr(owner)).status_code == 200
    assert open_reg.client.get(f"/v2/feedback", headers=hdr(owner)).json()["counts"]["handled"] == 1

    # 非法状态
    assert open_reg.client.post(f"/v2/feedback/{fid}/status", json={"status": "done"},
                                headers=hdr(owner)).status_code == 422
    # 多余字段一律拒绝
    assert open_reg.client.post(f"/v2/feedback/{fid}/status", json={"status": "new", "x": 1},
                                headers=hdr(owner)).status_code == 422
    # 不存在的编号
    assert open_reg.client.post("/v2/feedback/ffffffff/status", json={"status": "new"},
                                headers=hdr(owner)).status_code == 404
    assert open_reg.client.get("/v2/feedback/ffffffff", headers=hdr(owner)).status_code == 404

    # 审计里能看到谁改的、改成什么
    with open_reg.app.state.engine.begin() as c:
        rows = c.execute(text("SELECT event, result FROM audit WHERE event='feedback_status' ORDER BY ts")).all()
    assert ("feedback_status", "handled") in [tuple(r) for r in rows]


def test_filters_and_paging(open_reg):
    owner = make_owner(open_reg)
    ids = []
    for i in range(3):
        # 每提交一条换一个账号，避开"同账号 30 秒内只能提一次"的限制
        fid, _ = submit(open_reg, f"user{i}", key=f"key-0000000{i}", text=f"第 {i} 条")
        ids.append(fid)
        open_reg.clock.advance(60)
    open_reg.client.post(f"/v2/feedback/{ids[0]}/status", json={"status": "ignored"}, headers=hdr(owner))

    newest = open_reg.client.get("/v2/feedback?limit=2", headers=hdr(owner)).json()
    assert newest["total"] == 3 and len(newest["items"]) == 2 and newest["has_more"] is True
    assert newest["items"][0]["id"] == ids[2]          # 新的在前
    page2 = open_reg.client.get("/v2/feedback?limit=2&offset=2", headers=hdr(owner)).json()
    assert [i["id"] for i in page2["items"]] == [ids[0]] and page2["has_more"] is False

    ignored = open_reg.client.get("/v2/feedback?status=ignored", headers=hdr(owner)).json()
    assert [i["id"] for i in ignored["items"]] == [ids[0]]
    assert ignored["counts"] == {"new": 2, "handled": 0, "ignored": 1, "all": 3}

    assert open_reg.client.get("/v2/feedback?status=bogus", headers=hdr(owner)).status_code == 422
    assert open_reg.client.get("/v2/feedback?limit=500", headers=hdr(owner)).status_code == 422


def test_images_are_served_by_index_only(open_reg):
    fid, _ = submit(open_reg, "user1", images=[jpeg(), jpeg((20, 20))])
    owner = make_owner(open_reg)
    d = open_reg.client.get(f"/v2/feedback/{fid}", headers=hdr(owner)).json()
    assert [i["index"] for i in d["images"]] == [0, 1]
    assert all(i["bytes"] > 0 for i in d["images"])

    r = open_reg.client.get(f"/v2/feedback/{fid}/images/0", headers=hdr(owner))
    assert r.status_code == 200
    assert r.headers["content-type"] in ("image/jpeg", "image/png")
    assert r.content[:2] in (b"\xff\xd8", b"\x89P")   # JPEG / PNG 头
    assert r.headers["cache-control"] == "no-store"

    # 越界与不存在的条目
    assert open_reg.client.get(f"/v2/feedback/{fid}/images/2", headers=hdr(owner)).status_code == 404
    assert open_reg.client.get(f"/v2/feedback/{fid}/images/-1", headers=hdr(owner)).status_code == 404
    assert open_reg.client.get("/v2/feedback/ffffffff/images/0", headers=hdr(owner)).status_code == 404


def test_image_name_traversal_is_refused(open_reg):
    """库里万一出现带路径的图片名，也只当"不存在"，不去读目录外的文件。"""
    fid, _ = submit(open_reg, "user1", images=[jpeg()])
    owner = make_owner(open_reg)
    secret = open_reg.tmp / "outside.jpg"
    secret.write_bytes(b"\xff\xd8not-an-image")
    with open_reg.app.state.engine.begin() as c:
        c.execute(text("UPDATE feedback SET image_paths=:p WHERE id=:i"), {"p": '["../outside.jpg"]', "i": fid})
    assert open_reg.client.get(f"/v2/feedback/{fid}/images/0", headers=hdr(owner)).status_code == 404
    assert secret.is_file()   # 只拒绝读取，不删用户数据


def test_status_does_not_touch_other_columns(open_reg):
    fid, _ = submit(open_reg, "user1", text="原样保留")
    owner = make_owner(open_reg)
    open_reg.client.post(f"/v2/feedback/{fid}/status", json={"status": "handled"}, headers=hdr(owner))
    d = open_reg.client.get(f"/v2/feedback/{fid}", headers=hdr(owner)).json()
    assert d["text"] == "原样保留" and d["status"] == "handled"


def test_empty_inbox(open_reg):
    owner = make_owner(open_reg)
    body = open_reg.client.get("/v2/feedback", headers=hdr(owner)).json()
    assert body["items"] == [] and body["total"] == 0 and body["has_more"] is False
    assert body["counts"] == {"new": 0, "handled": 0, "ignored": 0, "all": 0}
