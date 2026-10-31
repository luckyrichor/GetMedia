import io
import json
import logging

import pytest
from PIL import Image

from conftest import hdr, register
from getmedia.config import MailConfig
from getmedia.feedback import MAX_PIXELS

CFG = MailConfig("to@example.invalid", "from@example.invalid", "smtp.example.invalid", 465, "ssl", "u@example.invalid", "pw-placeholder")


def jpeg(size=(40, 30), exif=False):
    im = Image.new("RGB", size, (200, 10, 10))
    buf = io.BytesIO()
    if exif:
        ex = Image.Exif()
        ex[0x010F] = "SecretCam"
        gps = ex.get_ifd(0x8825)
        gps[2] = (31.0, 13.0, 0.0); gps[1] = "N"
        im.save(buf, "JPEG", exif=ex)
    else:
        im.save(buf, "JPEG")
    return buf.getvalue()


@pytest.fixture
def user(open_reg):
    r = register(open_reg)
    tok = r.json()["session"]["token"]
    return open_reg, hdr(tok)


def post(env, h, key="key-00000001", text="闪退了", files=None, **form):
    data = {"text": text, "category": "bug", "idempotency_key": key, **form}
    return env.client.post("/v2/feedback", data=data, files=files or [], headers=h)


def img_file(b, name="a.jpg"):
    return ("images", (name, b, "application/octet-stream"))


def rows(env):
    with env.app.state.engine.begin() as c:
        return [dict(r._mapping) for r in c.exec_driver_sql("select * from feedback")]


def test_requires_login(open_reg):
    assert open_reg.client.post("/v2/feedback", data={"text": "x", "idempotency_key": "key-00000001"}).status_code == 401


def test_text_only_and_idempotent(user):
    env, h = user
    r = post(env, h)
    assert r.status_code == 201
    fid = r.json()["id"]
    env.clock.advance(1)
    assert post(env, h).json()["id"] == fid  # 重复提交不新增，也不受 30 秒间隔限制
    assert len(rows(env)) == 1


def test_empty_rejected_and_text_too_long(user):
    env, h = user
    assert post(env, h, text="  ").status_code == 422
    assert post(env, h, key="key-00000002", text="a" * 2001).status_code == 422


def test_diagnostics_whitelist_and_filter(user):
    env, h = user
    d = {"app_version": "0.1.0", "channel": "android", "cookie": "x", "last_error_code": "http://a/b?token=1",
         "os": "Android 14 Authorization: Bearer abc", "device": "Pixel", "extra": "zzz"}
    post(env, h, diagnostics=json.dumps(d))
    got = json.loads(rows(env)[0]["diagnostics_json"])
    assert got == {"app_version": "0.1.0", "channel": "android", "device": "Pixel"}


def test_interval_and_daily_limits(user):
    env, h = user
    assert post(env, h, key="key-00000001").status_code == 201
    assert post(env, h, key="key-00000002").status_code == 429  # <30 秒
    for i in range(3, 6):
        env.clock.advance(31)
        assert post(env, h, key=f"key-0000000{i}").status_code == 201
    env.clock.advance(31)
    assert post(env, h, key="key-00000006").status_code == 201  # 第 5 条
    env.clock.advance(31)
    assert post(env, h, key="key-00000007").status_code == 429  # 超每账号每天 5 条


def test_ip_daily_limit(open_reg):
    env = open_reg
    n = 0
    for i in range(3):
        tok = register(env, name=f"user{i}").json()["session"]["token"]
        for j in range(4):
            env.clock.advance(31)
            r = post(env, hdr(tok), key=f"k{i}-{j}-000000")
            if r.status_code == 201:
                n += 1
    assert n == 10


def test_image_exif_stripped_and_reencoded(user):
    env, h = user
    src = jpeg(exif=True)
    assert b"SecretCam" in src
    r = post(env, h, files=[img_file(src)])
    assert r.status_code == 201
    row = rows(env)[0]
    name = json.loads(row["image_paths"])[0]
    data = (env.settings.feedback_image_dir / name).read_bytes()
    assert b"SecretCam" not in data and not Image.open(io.BytesIO(data)).getexif()
    assert oct((env.settings.feedback_image_dir / name).stat().st_mode & 0o777) == "0o600"
    assert name.endswith(".jpg") and "a.jpg" not in name


def test_fake_extension_and_non_image_rejected(user):
    env, h = user
    assert post(env, h, files=[img_file(b"MZ\x90\x00not an image", "x.png")]).status_code == 422
    assert post(env, h, key="key-00000002", files=[img_file(b"\xff\xd8\xff" + b"garbage" * 10)]).status_code in (422, 429)
    assert rows(env) == []


def test_pixel_bomb_rejected(user):
    env, h = user
    side = int(MAX_PIXELS ** 0.5) + 10
    buf = io.BytesIO()
    Image.new("1", (side, side)).save(buf, "PNG")
    assert len(buf.getvalue()) < 1_000_000
    r = post(env, h, files=[img_file(buf.getvalue())])
    assert r.status_code == 422 and rows(env) == []


def test_too_many_or_too_big(user):
    env, h = user
    four = [img_file(jpeg()) for _ in range(4)]
    assert post(env, h, files=four).status_code == 413
    big = jpeg() + b"\0" * (5 * 1024 * 1024)
    assert post(env, h, key="key-00000002", files=[img_file(big)]).status_code in (413, 429)


def test_cross_user_cannot_replay_key(open_reg):
    env = open_reg
    a = hdr(register(env, name="alice").json()["session"]["token"])
    b = hdr(register(env, name="bobby").json()["session"]["token"])
    ia = post(env, a).json()["id"]
    ib = post(env, b).json()["id"]
    assert ia != ib


def test_mail_sent_with_correct_content_no_replyto(user):
    env, h = user
    env.app.state.fb.s = env.settings.with_(mail=CFG)
    post(env, h, text="hello\nBcc: evil@example.invalid", contact="me@example.invalid\r\nBcc: x@example.invalid",
         diagnostics=json.dumps({"app_version": "0.1.0\r\nBcc: z", "channel": "android"}))
    sent = []
    assert env.app.state.fb.process_queue(sent.append) == 1
    m = sent[0]
    assert m["To"] == CFG.to and m["Reply-To"] is None and m["Bcc"] is None
    assert m["Subject"].startswith("[GetMedia 反馈] ") and "android" in m["Subject"]
    assert "\n" not in m["Subject"]
    assert m.get_content_type() == "text/plain"
    assert rows(env)[0]["mail_state"] == "sent"


def test_smtp_failure_keeps_record_backoff_and_logs_no_address(user, caplog):
    env, h = user
    fb = env.app.state.fb
    fb.s = env.settings.with_(mail=CFG)
    assert post(env, h).status_code == 201

    class Boom(Exception):
        smtp_code = 535

    def bad(_m):
        raise Boom("auth failed for to@example.invalid pw-placeholder")
    with caplog.at_level(logging.DEBUG):
        fb.process_queue(bad)
    row = rows(env)[0]
    assert row["mail_state"] == "pending" and row["email_attempts"] == 1 and row["next_attempt_at"] > env.clock()
    assert "535" in caplog.text and "Boom" in caplog.text
    assert "example.invalid" not in caplog.text and "pw-placeholder" not in caplog.text
    assert fb.process_queue(bad) == 0  # 退避期内不重试
    env.clock.advance(61)
    sent = []
    fb.process_queue(sent.append)
    assert rows(env)[0]["mail_state"] == "sent"


def test_no_mail_config_still_stored(user):
    env, h = user
    assert post(env, h).status_code == 201
    env.app.state.fb.process_queue()
    assert rows(env)[0]["mail_state"] == "skipped" and rows(env)[0]["text"] == "闪退了"


def test_daily_mail_cap(user):
    env, h = user
    fb = env.app.state.fb
    fb.s = env.settings.with_(mail=CFG, feedback_daily_mail_cap=1, feedback_account_limit=(99, 86400), feedback_ip_limit=(99, 86400))
    for i in range(2):
        env.clock.advance(31)
        post(env, h, key=f"key-0000000{i}")
    sent = []
    fb.process_queue(sent.append)
    assert len(sent) == 1
    assert sorted(r["mail_state"] for r in rows(env)) == ["sent", "skipped"]


def test_image_cap_rejects_and_alerts(user):
    env, h = user
    env.app.state.fb.s = env.settings.with_(feedback_image_total_cap=10)
    r = post(env, h, files=[img_file(jpeg())])
    assert r.status_code == 507
    assert any(k == "feedback_images_full" for k, _ in env.alerts.items)
    assert post(env, h, key="key-00000002").status_code in (201, 429)


def test_retention_cleanup(user):
    env, h = user
    fb = env.app.state.fb
    post(env, h, files=[img_file(jpeg())])
    name = json.loads(rows(env)[0]["image_paths"])[0]
    env.clock.advance(91 * 86400)
    out = fb.cleanup()
    assert out["images"] == 1 and not (env.settings.feedback_image_dir / name).exists()
    assert len(rows(env)) == 1 and rows(env)[0]["text"]
    env.clock.advance(90 * 86400)
    assert fb.cleanup()["texts"] == 1 and rows(env) == []


def test_no_secret_in_response_or_repr(user):
    env, h = user
    assert "pw-placeholder" not in repr(CFG) and "example.invalid" not in repr(CFG)


def test_cli_list_show_export_mark(user):
    import io as _io
    from getmedia.cli import main
    env, h = user
    fid = post(env, h, files=[img_file(jpeg())], contact="me@example.invalid").json()["id"]
    out = _io.StringIO()
    assert main(["feedback", "list"], env.settings, out) == 0 and fid[:8] in out.getvalue()
    out = _io.StringIO()
    assert main(["feedback", "show", fid[:8]], env.settings, out) == 0
    assert "图片：" in out.getvalue() and "闪退了" in out.getvalue()
    out = _io.StringIO()
    assert main(["feedback", "export"], env.settings, out) == 0 and fid in out.getvalue()
    assert main(["feedback", "mark-handled", fid[:8]], env.settings, _io.StringIO()) == 0
    out = _io.StringIO()
    main(["feedback", "list"], env.settings, out)
    assert fid[:8] not in out.getvalue()
    assert main(["feedback", "show", "zzzz"], env.settings, _io.StringIO()) == 1
