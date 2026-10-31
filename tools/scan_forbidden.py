#!/usr/bin/env python3
"""禁用词扫描：证明免费版里"不存在"重型功能（编译期保证的检查手段）。

用法：
    python3 tools/scan_forbidden.py heavy  PATH...   # 免费版源码、反编译结果、web-public 产物
    python3 tools/scan_forbidden.py legacy PATH...   # 旧产品名残留（GetAudio/GetVideo 等）

词表在 tools/forbidden-heavy.txt 与 tools/forbidden-legacy.txt：
    每行一个词，按子串区分大小写匹配；以 `re:` 开头为正则；`#` 开头为注释。
只打印 "文件:行号: 词表行号"，不打印整行内容。无命中退出 0，有命中退出 1。
文本文件按 UTF-8 读取；二进制文件（如反编译后的 dex、APK 内的资源）按字节搜索。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
LISTS = {"heavy": HERE / "forbidden-heavy.txt", "legacy": HERE / "forbidden-legacy.txt"}
SKIP_DIRS = {".git", "node_modules", "build", ".gradle", "__pycache__"}


def load_terms(path: Path) -> list[tuple[int, str, re.Pattern[bytes]]]:
    terms = []
    for no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        pattern = line[3:] if line.startswith("re:") else re.escape(line)
        terms.append((no, line, re.compile(pattern.encode("utf-8"))))
    return terms


def scan_bytes(display: str, data: bytes, terms) -> list[str]:
    hits = []
    for no, _line, rx in terms:
        if not rx.search(data):
            continue
        # 尽量给出行号：按行再找一遍；二进制文件没有有意义的行号则只给文件
        for lineno, chunk in enumerate(data.split(b"\n"), 1):
            if rx.search(chunk):
                hits.append(f"{display}:{lineno}: 命中禁用词表第 {no} 行")
                break
    return hits


def scan_paths(paths: list[str], terms) -> list[str]:
    hits: list[str] = []
    for raw in paths:
        root = Path(raw)
        files = [root] if root.is_file() else sorted(
            f for f in root.rglob("*") if f.is_file() and not (SKIP_DIRS & set(f.parts)))
        for f in files:
            try:
                data = f.read_bytes()
            except OSError:
                continue
            hits += scan_bytes(f.as_posix(), data, terms)
    return hits


def main(argv: list[str]) -> int:
    if len(argv) < 2 or argv[0] not in LISTS:
        print(__doc__); return 2
    terms = load_terms(LISTS[argv[0]])
    hits = scan_paths(argv[1:], terms)
    for h in hits:
        print(h)
    if hits:
        print(f"禁用词扫描（{argv[0]}）：{len(hits)} 个文件命中。", file=sys.stderr)
        return 1
    print(f"禁用词扫描（{argv[0]}）通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
