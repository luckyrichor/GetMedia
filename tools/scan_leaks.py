#!/usr/bin/env python3
"""仓库泄露扫描：拒绝把凭据、邮箱、数据库、密钥、服务器内部细节提交进仓库。

用法：
    python3 tools/scan_leaks.py --staged     # 扫描暂存区（pre-commit 用）
    python3 tools/scan_leaks.py --all        # 扫描所有已跟踪文件
    python3 tools/scan_leaks.py PATH...      # 扫描指定文件或目录（含未跟踪）

只打印 "文件:行号: 规则名"，**绝不打印命中的内容**（命中的可能正是密钥）。
某一行确需放行（例如测试用的假值），在该行末尾加注释 `leakscan:allow`。
退出码：0 无命中；1 有命中；2 用法错误。
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

MAX_BYTES = 1_000_000
ALLOW_MARK = "leakscan:allow"

# 一律不得入库的文件名（`*.example` 模板除外）。
FORBIDDEN_NAMES = [
    (re.compile(r"(^|/)\.env$"), "env 文件"),
    (re.compile(r"(^|/)[^/]*\.env$"), "env 文件"),
    (re.compile(r"\.(db|sqlite|sqlite3)(-wal|-shm|-journal)?$"), "数据库文件"),
    (re.compile(r"\.(jks|keystore|p12|pfx|pem|key|age)$"), "密钥/证书文件"),
    (re.compile(r"(^|/)keystore\.properties$"), "签名属性文件"),
    (re.compile(r"(^|/)getmedia\.properties$"), "签名属性文件"),
    (re.compile(r"(^|/)(\.)?htpasswd$"), "htpasswd"),
    (re.compile(r"(^|/)server-access\.json$"), "设备访问凭据"),
    (re.compile(r"(^|/)\.secret_key$"), "本地密钥"),
    (re.compile(r"\.(apk|aab)$"), "构建产物"),
    (re.compile(r"(^|/)(snapshots|feedback-images)/"), "运行期数据目录"),
]

ALLOWED_EMAIL_DOMAINS = {
    "example.invalid", "example.com", "example.org", "example.net",
    "users.noreply.github.com", "noreply.github.com", "github.com", "anthropic.com",
}

EMAIL = re.compile(r"[A-Za-z0-9._%+\-]+@([A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,})")
PLACEHOLDER = re.compile(r"^(<.*>|\$\{?.*|%.*|change.?me|x{3,}|example.*|placeholder.*|your[-_ ].*|\.{3}|\*+|none|null|)$", re.I)
SECRET_ASSIGN = re.compile(
    r"\b([A-Z][A-Z0-9_]*(?:PASSWORD|SECRET|TOKEN|API_KEY|AUTH_CODE|SECRET_KEY)|FEEDBACK_MAIL_TO|FEEDBACK_SMTP_USER)"
    r"\s*[=:]\s*['\"]?([^\s'\"#]*)")

CONTENT_RULES = [
    (re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"), "私钥块"),
    (re.compile(r"\bsk-[A-Za-z0-9]{20,}"), "疑似 API 密钥"),
    (re.compile(r"\bAKID[A-Za-z0-9]{16,}"), "疑似云 API 密钥"),
    (re.compile(r"\b(ghp|gho|ghs|ghu)_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{20,}"), "疑似 GitHub 令牌"),
    (re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}"), "疑似 Slack 令牌"),
    (re.compile(r"Authorization:\s*Basic\s+[A-Za-z0-9+/=]{12,}", re.I), "Basic 凭据"),
    (re.compile(r"Authorization:\s*Bearer\s+[A-Za-z0-9._\-]{20,}", re.I), "Bearer 令牌"),
    (re.compile(r"授权码\s*[:：=]\s*['\"]?[a-z]{16}\b"), "疑似邮箱授权码"),
    # 服务器内部细节（方案 §14.4：实例 ID、防火墙规则、SSH 别名、飞书 token 不进仓库）
    (re.compile(r"\blhins-[a-z0-9]{6,}"), "云实例 ID"),
    (re.compile(r"\bssh\s+tx\b"), "SSH 别名"),
    (re.compile(r"feishu\.cn/(wiki|docx|docs)/[A-Za-z0-9]{10,}"), "飞书文档链接/令牌"),
]


def scan_name(path: str) -> list[str]:
    if path.endswith(".example") or ".example." in path.rsplit("/", 1)[-1]:
        return []
    return [label for rx, label in FORBIDDEN_NAMES if rx.search(path)]


def scan_text(text: str) -> list[tuple[int, str]]:
    hits: list[tuple[int, str]] = []
    for no, line in enumerate(text.splitlines(), 1):
        if ALLOW_MARK in line:
            continue
        for m in EMAIL.finditer(line):
            if m.group(1).lower() not in ALLOWED_EMAIL_DOMAINS:
                hits.append((no, "邮箱地址"))
                break
        for m in SECRET_ASSIGN.finditer(line):
            if not PLACEHOLDER.match(m.group(2).strip()):
                hits.append((no, f"{m.group(1)} 带真实值"))
                break
        for rx, label in CONTENT_RULES:
            if rx.search(line):
                hits.append((no, label))
    return hits


def scan_file(display: str, data: bytes) -> list[str]:
    out = [f"{display}: 文件名不允许入库（{label}）" for label in scan_name(display)]
    if len(data) > MAX_BYTES or b"\0" in data[:4096]:
        return out
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        return out
    out += [f"{display}:{no}: {label}" for no, label in scan_text(text)]
    return out


def _git(*args: str) -> bytes:
    return subprocess.run(["git", *args], check=True, capture_output=True).stdout


def staged_files() -> list[tuple[str, bytes]]:
    names = _git("diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z").split(b"\0")
    return [(n.decode(), _git("show", ":" + n.decode())) for n in names if n]


def tracked_files() -> list[tuple[str, bytes]]:
    names = _git("ls-files", "-z").split(b"\0")
    out = []
    for n in names:
        if n:
            p = Path(n.decode())
            if p.is_file():
                out.append((n.decode(), p.read_bytes()))
    return out


def path_files(paths: list[str]) -> list[tuple[str, bytes]]:
    out = []
    for raw in paths:
        p = Path(raw)
        for f in ([p] if p.is_file() else sorted(x for x in p.rglob("*") if x.is_file() and ".git" not in x.parts)):
            out.append((f.as_posix(), f.read_bytes()))
    return out


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__); return 2
    if argv == ["--staged"]:
        files = staged_files()
    elif argv == ["--all"]:
        files = tracked_files()
    elif argv[0].startswith("--"):
        print(__doc__); return 2
    else:
        files = path_files(argv)
    problems = [p for name, data in files for p in scan_file(name, data)]
    for p in problems:
        print(p)
    if problems:
        print(f"泄露扫描：{len(problems)} 处命中，已拒绝。内容未打印；请检查对应位置。", file=sys.stderr)
        return 1
    print(f"泄露扫描通过（{len(files)} 个文件）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
