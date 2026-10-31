import importlib.util
import sys
from pathlib import Path

spec = importlib.util.spec_from_file_location("scan_leaks", Path(__file__).parents[1] / "scan_leaks.py")
m = importlib.util.module_from_spec(spec)
sys.modules["scan_leaks"] = m
spec.loader.exec_module(m)

AT = "@"


def hits(text):
    return [label for _, label in m.scan_text(text)]


def test_real_looking_email_flagged():
    assert "邮箱地址" in hits("to: someone" + AT + "qq.com")


def test_allowed_emails_pass():
    assert hits("a" + AT + "example.invalid b" + AT + "example.com noreply" + AT + "anthropic.com") == []


def test_secret_assignment_real_value_flagged():
    assert any("带真实值" in h for h in hits("FEEDBACK_SMTP_PASSWORD=" + "abcdefghijklmnop"))


def test_secret_assignment_placeholders_pass():
    for v in ["<填写>", "${X}", "changeme", "xxxx", "your-password", ""]:
        assert hits("FEEDBACK_SMTP_PASSWORD=" + v) == [], v


def test_private_key_block():
    assert "私钥块" in hits("-----BEGIN " + "RSA PRIVATE KEY-----")


def test_tokens():
    assert hits("t = ghp_" + "a" * 36)
    assert hits("k = sk-" + "a" * 24)
    assert hits("Authorization: Bearer " + "a" * 30)


def test_auth_code_phrase():
    assert "疑似邮箱授权码" in hits("授权码：" + "abcdefghijklmnop")


def test_server_internals():
    assert hits("ssh " + "tx")
    assert hits("lhins-" + "abcd1234")
    assert hits("https://x.feishu.cn/wiki/" + "A" * 20)


def test_allow_marker():
    assert hits("to: someone" + AT + "qq.com  # leakscan:allow") == []


def test_forbidden_filenames():
    for n in [".env", "a/b.db", "x.jks", "keystore.properties", "app.apk", "a/snapshots/x"]:
        assert m.scan_name(n), n
    assert m.scan_name("deploy/feedback.env.example") == []
    assert m.scan_name("server/getmedia/db.py") == []


def test_output_never_contains_value(tmp_path, capsys):
    secret = "zzzzzzzzzzzzzzzz"
    f = tmp_path / "a.txt"
    f.write_text("授权码：" + secret + "\n", encoding="utf-8")
    assert m.main([str(f)]) == 1
    cap = capsys.readouterr()
    assert secret not in cap.out + cap.err


def test_binary_and_large_skipped():
    assert m.scan_file("a.bin", b"\0\1\2" + b"ghp_" + b"a" * 40) == []
