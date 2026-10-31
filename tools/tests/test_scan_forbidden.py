import importlib.util
import sys
from pathlib import Path

spec = importlib.util.spec_from_file_location("scan_forbidden", Path(__file__).parents[1] / "scan_forbidden.py")
m = importlib.util.module_from_spec(spec)
sys.modules["scan_forbidden"] = m
spec.loader.exec_module(m)


def run(kind, path):
    return m.main([kind, str(path)])


def test_heavy_hits_route_and_class(tmp_path, capsys):
    (tmp_path / "a.kt").write_text('val u = "/v2/" + "creators"\nclass Creator' + 'Batch\n', encoding="utf-8")
    assert run("heavy", tmp_path) == 1
    assert "a.kt:2" in capsys.readouterr().out


def test_heavy_clean(tmp_path):
    (tmp_path / "a.kt").write_text("博主主页批量提取 暂未开放\n", encoding="utf-8")
    assert run("heavy", tmp_path) == 0


def test_heavy_binary_bytes(tmp_path):
    (tmp_path / "classes.dex").write_bytes(b"\0\1" + b"v2/" + b"extract" + b"\0")
    assert run("heavy", tmp_path) == 1


def test_legacy_hits(tmp_path):
    (tmp_path / "x.py").write_text("import " + "get" + "audio\n", encoding="utf-8")
    assert run("legacy", tmp_path) == 1


def test_skip_dirs(tmp_path):
    (tmp_path / "node_modules").mkdir()
    (tmp_path / "node_modules" / "x.js").write_text("agent/" + "chat", encoding="utf-8")
    assert run("heavy", tmp_path) == 0


def test_owner_regex_case_insensitive(tmp_path):
    (tmp_path / "x.ts").write_text("const Owner" + "Panel = 1", encoding="utf-8")
    assert run("heavy", tmp_path) == 1


def test_usage_error():
    assert m.main([]) == 2
    assert m.main(["nope", "."]) == 2
