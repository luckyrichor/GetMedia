"""平台凭据加密：只测密文行为，不涉及任何真实 Cookie。"""
from __future__ import annotations

import pytest

from getmedia.credentials import CredentialError, CredentialVault
from getmedia.config import decode_credential_key

KEY = b"k" * 32


def test_round_trip():
    v = CredentialVault(KEY)
    blob = v.seal("SESSDATA=abc; bili_jct=def")
    assert blob.startswith(b"gm1")
    assert b"abc" not in blob  # 明文不出现在密文里
    assert v.open(blob) == "SESSDATA=abc; bili_jct=def"


def test_same_plaintext_differs_each_time():
    v = CredentialVault(KEY)
    assert v.seal("same") != v.seal("same")


def test_tampered_blob_is_rejected():
    v = CredentialVault(KEY)
    blob = bytearray(v.seal("cookie"))
    blob[-1] ^= 0x01
    with pytest.raises(CredentialError):
        v.open(bytes(blob))


def test_wrong_key_is_rejected():
    blob = CredentialVault(KEY).seal("cookie")
    with pytest.raises(CredentialError):
        CredentialVault(b"z" * 32).open(blob)


def test_missing_key_refuses_instead_of_plaintext():
    v = CredentialVault(b"")
    assert v.available is False
    with pytest.raises(CredentialError):
        v.seal("cookie")
    with pytest.raises(CredentialError):
        v.open(b"gm1whatever")


def test_empty_or_unknown_blob():
    v = CredentialVault(KEY)
    for bad in (b"", None, b"plain-text", b"gm1short"):
        with pytest.raises(CredentialError):
            v.open(bad)


def test_key_decoding_and_validation():
    import base64

    raw = base64.urlsafe_b64encode(b"a" * 32).decode()
    assert decode_credential_key(raw) == b"a" * 32
    assert decode_credential_key("") == b""
    with pytest.raises(Exception):
        decode_credential_key("not-base64!!")
    with pytest.raises(Exception):
        decode_credential_key(base64.urlsafe_b64encode(b"short").decode())
