"""release 构建必须拒绝占位 serverBase（debug 只警告）。

这里是真的跑 Gradle 配置阶段（`--dry-run`，不产出 APK），
因为规则写在 `android/app/build.gradle.kts` 里，脱离 Gradle 无法验证。

跳过条件：没有 gradlew / 没有 Android SDK / 显式设置 GETMEDIA_SKIP_GRADLE=1。
"""
from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "android"
PLACEHOLDER = "https://example.invalid/getmedia/api"
REAL_BASE = "https://api.example.com/getmedia/api"


def _sdk_dir() -> str | None:
    local = ANDROID / "local.properties"
    if local.is_file():
        for line in local.read_text().splitlines():
            if line.startswith("sdk.dir="):
                return line.split("=", 1)[1].strip()
    return os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")


def _java_home() -> str | None:
    if os.environ.get("JAVA_HOME"):
        return os.environ["JAVA_HOME"]
    candidate = Path("/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home")
    return str(candidate) if candidate.is_dir() else None


pytestmark = pytest.mark.skipif(
    os.environ.get("GETMEDIA_SKIP_GRADLE") == "1"
    or not (ANDROID / "gradlew").is_file()
    or (shutil.which("java") is None and _java_home() is None),
    reason="没有 Gradle / JDK，跳过需要真实构建的用例",
)


def gradle(*args: str) -> subprocess.CompletedProcess:
    env = dict(os.environ)
    java_home = _java_home()
    if java_home:
        env["JAVA_HOME"] = java_home
    sdk = _sdk_dir()
    if sdk:
        env["ANDROID_HOME"] = sdk
        env["ANDROID_SDK_ROOT"] = sdk
    env.setdefault("GRADLE_OPTS", "-Dorg.gradle.jvmargs=-Xmx2g")
    return subprocess.run(
        [str(ANDROID / "gradlew"), "--console=plain", *args],
        cwd=ANDROID, env=env, capture_output=True, text=True, timeout=600,
    )


def _combined(result: subprocess.CompletedProcess) -> str:
    return (result.stdout or "") + (result.stderr or "")


def test_release_build_fails_on_placeholder():
    result = gradle(
        ":app:assembleFreeRelease", "--dry-run",
        f"-PgetmediaServerBase={PLACEHOLDER}",
        # 本机/CI 可能没有发布密钥；这里只验证 serverBase 规则，签名检查让路
        "-PallowDebugSignRelease=true",
    )
    assert result.returncode != 0, _combined(result)[-2000:]
    output = _combined(result)
    assert "占位 serverBase" in output
    assert "release 构建不允许" in output


def test_release_build_accepts_real_base():
    result = gradle(
        ":app:assembleFreeRelease", "--dry-run",
        f"-PgetmediaServerBase={REAL_BASE}",
        "-PallowDebugSignRelease=true",
    )
    assert result.returncode == 0, _combined(result)[-2000:]
    assert "占位 serverBase" not in _combined(result)
