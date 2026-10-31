"""登录名与密码校验。错误只返回固定文案，不回显输入。"""
from __future__ import annotations

import re
import unicodedata

_NAME_RE = re.compile(r"^[a-z0-9_-]{3,32}$")


def normalize_login_name(raw: str) -> str:
    """NFKC 规范化并转小写（登录与注册共用，保证等价输入落到同一个键）。"""
    return unicodedata.normalize("NFKC", raw).strip().lower()


def login_name_problem(norm: str) -> str | None:
    if not _NAME_RE.fullmatch(norm):
        return "登录名需为 3–32 位小写字母、数字、下划线或连字符"
    digits = re.sub(r"[-_]", "", norm)
    if digits.isdigit() and len(digits) >= 7:
        return "登录名不能是手机号或纯长数字"
    return None


def password_problem(password: str, norm_name: str) -> str | None:
    if not (8 <= len(password) <= 128):
        return "密码长度需为 8–128 位"
    if "\x00" in password:
        return "密码包含不允许的字符"
    if normalize_login_name(password) == norm_name:
        return "密码不能与登录名相同"
    return None
