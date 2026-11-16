"""平台凭据（Cookie / token）的加密存储。

约束（来自方案与任务书）：
- Cookie 等同于账号本身，**只能密文入库**；明文不落盘、不进日志、不进诊断。
- 密钥只从 ``GETMEDIA_CRED_KEY`` 读取（32 字节 base64url），不进仓库、不进客户端。
- 未配置密钥时**不降级成明文**：相关接口返回 503，等功能被显式打开。

AES-256-GCM：自带完整性校验，密文被改动时解密直接失败，不会解出一段垃圾。
密文格式：``b"gm1" + nonce(12) + ciphertext``，前缀留给日后换算法/换密钥。
"""
from __future__ import annotations

import os
import time

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

PREFIX = b"gm1"
NONCE_BYTES = 12


class CredentialError(RuntimeError):
    """凭据不可用：未配置密钥、密文损坏或被篡改。消息可直接展示给管理员。"""


class CredentialVault:
    def __init__(self, key: bytes, *, clock=time.time):
        if key and len(key) != 32:
            raise CredentialError("凭据密钥必须是 32 字节")
        self._key = key
        self._clock = clock

    @property
    def available(self) -> bool:
        return bool(self._key)

    def seal(self, plaintext: str) -> bytes:
        if not self._key:
            raise CredentialError("未配置 GETMEDIA_CRED_KEY，无法保存平台凭据")
        nonce = os.urandom(NONCE_BYTES)
        body = AESGCM(self._key).encrypt(nonce, plaintext.encode("utf-8"), PREFIX)
        return PREFIX + nonce + body

    def open(self, blob: bytes | None) -> str:
        if not self._key:
            raise CredentialError("未配置 GETMEDIA_CRED_KEY，无法读取平台凭据")
        if not blob:
            raise CredentialError("凭据为空")
        if not blob.startswith(PREFIX) or len(blob) <= len(PREFIX) + NONCE_BYTES:
            raise CredentialError("凭据格式不可识别")
        nonce = blob[len(PREFIX):len(PREFIX) + NONCE_BYTES]
        body = blob[len(PREFIX) + NONCE_BYTES:]
        try:
            return AESGCM(self._key).decrypt(nonce, body, PREFIX).decode("utf-8")
        except (InvalidTag, UnicodeDecodeError):
            raise CredentialError("凭据无法解密：密钥不匹配或密文被改动") from None
