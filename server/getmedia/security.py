"""密码哈希（Argon2id，来自 cryptography）、令牌、恢复码。"""
from __future__ import annotations

import base64
import hashlib
import hmac
import secrets
import threading

from cryptography.exceptions import InvalidKey
from cryptography.hazmat.primitives.kdf.argon2 import Argon2id

_B32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"


def _b64(b: bytes) -> str:
    return base64.b64encode(b).decode().rstrip("=")


def _unb64(s: str) -> bytes:
    return base64.b64decode(s + "=" * (-len(s) % 4))


class PasswordHasher:
    """参数写入哈希串：$argon2id$v=19$m=<KiB>,t=<迭代>,p=1$<盐>$<哈希>。并发哈希数受信号量限制。"""

    def __init__(self, memory_kib: int, iterations: int, concurrency: int = 2):
        self.m, self.t = memory_kib, iterations
        self._sem = threading.BoundedSemaphore(concurrency)
        self.fake_hash = self.hash("fake-password-for-timing")

    def hash(self, password: str) -> str:
        salt = secrets.token_bytes(16)
        with self._sem:
            dk = Argon2id(salt=salt, length=32, iterations=self.t, lanes=1, memory_cost=self.m).derive(password.encode())
        return f"$argon2id$v=19$m={self.m},t={self.t},p=1${_b64(salt)}${_b64(dk)}"

    def verify(self, stored: str, password: str) -> bool:
        try:
            _, algo, _v, params, salt, dk = stored.split("$")
            if algo != "argon2id":
                return False
            kv = dict(p.split("=") for p in params.split(","))
            m, t, p = int(kv["m"]), int(kv["t"]), int(kv["p"])
            expected = _unb64(dk)
            with self._sem:
                Argon2id(salt=_unb64(salt), length=len(expected), iterations=t, lanes=p, memory_cost=m).verify(password.encode(), expected)
            return True
        except (InvalidKey, ValueError, KeyError):
            return False


def new_token() -> str:
    return secrets.token_urlsafe(32)


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def new_id(prefix: str) -> str:
    return f"{prefix}_{secrets.token_hex(8)}"


def new_recovery_code() -> str:
    """16 位 base32（80 位熵），展示为 4 组。"""
    raw = base64.b32encode(secrets.token_bytes(10)).decode()
    return "-".join(raw[i:i + 4] for i in range(0, 16, 4))


def normalize_recovery_code(code: str) -> str:
    return "".join(ch for ch in code.upper() if ch in _B32_ALPHABET)


def recovery_hmac(secret_key: bytes, code: str) -> str:
    return hmac.new(secret_key, b"recovery:" + normalize_recovery_code(code).encode(), hashlib.sha256).hexdigest()


def new_invite_code() -> str:
    return "-".join(base64.b32encode(secrets.token_bytes(8)).decode().rstrip("=")[i:i + 4] for i in range(0, 12, 4))


def invite_hash(secret_key: bytes, code: str) -> str:
    return hmac.new(secret_key, b"invite:" + normalize_recovery_code(code).encode(), hashlib.sha256).hexdigest()
