"""release.json 的字段校验：宁可少给，也不下发错包或坏链接。"""
from getmedia.release import _clean

SHA_FULL = "a" * 64
SHA_FREE = "b" * 64
APK_FULL = "https://example.invalid/GetMedia-full-0.1.28.apk"
APK_FREE = "https://example.invalid/GetMedia-free-0.1.28.apk"


def latest(**over):
    d = {"version": "0.1.28", "notes": "说明", "apk": APK_FULL, "sha256": SHA_FULL}
    d.update(over)
    return {"min_version": "0.1.0", "latest": d}


def test_clean_keeps_full_build_only():
    out = _clean(latest())
    assert out["min_version"] == "0.1.0"
    assert out["latest"] == {"version": "0.1.28", "notes": "说明", "apk": APK_FULL, "sha256": SHA_FULL}


def test_clean_keeps_free_build():
    """免费版是另一个包名，必须单独给链接；客户端据此下载，不会拿到完整版的包。"""
    out = _clean(latest(free={"apk": APK_FREE, "sha256": SHA_FREE}))
    assert out["latest"]["apk"] == APK_FULL
    assert out["latest"]["free"] == {"apk": APK_FREE, "sha256": SHA_FREE}


def test_clean_drops_free_build_with_bad_link():
    # 非 https / 哈希长度不对 / 缺字段：整组丢弃，绝不能退化成"免费版下载完整版"
    for bad in (
        {"apk": "http://example.invalid/free.apk", "sha256": SHA_FREE},
        {"apk": APK_FREE, "sha256": "short"},
        {"apk": APK_FREE},
        {"sha256": SHA_FREE},
        "not-a-dict",
    ):
        out = _clean(latest(free=bad))
        assert "free" not in out["latest"], bad
        assert out["latest"]["apk"] == APK_FULL


def test_clean_drops_bad_full_link():
    # 一组 {apk, sha256} 里任一不合格就整组丢弃：宁可不提示，也不下发无法校验的链接
    out = _clean(latest(apk="ftp://example.invalid/x.apk"))
    assert out["latest"]["apk"] is None and out["latest"]["sha256"] is None
    out = _clean(latest(sha256="A" * 64))
    assert out["latest"]["apk"] is None and out["latest"]["sha256"] is None


def test_clean_ignores_bad_version():
    assert _clean({"min_version": "1", "latest": {"version": "x"}}) == {"min_version": None, "latest": None}
    assert _clean({"latest": {"version": "0.1.28", "notes": "n" * 5000}})["latest"]["notes"] == "n" * 2000
