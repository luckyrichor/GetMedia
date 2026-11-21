"""批量提取的输出格式：把取到的原始文件转成用户勾选的容器 / 编码。

服务器的 ffmpeg（Ubuntu 24.04 自带 6.1，含 libx264 / libvpx-vp9 / libmp3lame / flac / libopus）
负责转换。规则上尽量「不重编码」：mp4 / mkv 只换封装（``-c copy``），m4a 直接抽音轨，
只有 mp3 / flac / wav / webm 真的重编码——那几种明显更慢，界面上会提示。

``source`` 表示原样保存平台给的文件，不经过 ffmpeg，所以服务器没装 ffmpeg 也能用。
"""
from __future__ import annotations

import logging
import shutil
import subprocess
from pathlib import Path

log = logging.getLogger("getmedia.creator_formats")

# key -> 扩展名；``source`` 的扩展名由上游文件决定，这里留空
FORMATS: dict[str, str | None] = {
    "source": None,
    "mp4": "mp4",
    "mkv": "mkv",
    "webm": "webm",
    "m4a": "m4a",
    "mp3": "mp3",
    "flac": "flac",
    "wav": "wav",
}

VIDEO_FORMATS = ("source", "mp4", "mkv", "webm")
AUDIO_FORMATS = ("m4a", "mp3", "flac", "wav")

# 首选参数：能直接复制流就复制（毫秒级，不占 CPU）
_ARGS: dict[str, list[str]] = {
    "mp4": ["-c", "copy", "-movflags", "+faststart"],
    "mkv": ["-c", "copy"],
    "webm": ["-c:v", "libvpx-vp9", "-crf", "32", "-b:v", "0", "-c:a", "libopus", "-b:a", "128k"],
    "m4a": ["-vn", "-c:a", "copy"],
    "mp3": ["-vn", "-c:a", "libmp3lame", "-b:a", "192k"],
    "flac": ["-vn", "-c:a", "flac"],
    "wav": ["-vn", "-c:a", "pcm_s16le"],
}

# 源文件的编码放不进目标容器时（比如 av1 进 mp4、opus 进 m4a）退回重编码
_FALLBACK: dict[str, list[str]] = {
    "mp4": ["-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-c:a", "aac", "-b:a", "160k"],
    "mkv": ["-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-c:a", "aac"],
    "m4a": ["-vn", "-c:a", "aac", "-b:a", "192k"],
}


class FormatError(Exception):
    """转换失败（缺 ffmpeg、源文件损坏、磁盘写不进……）。"""


def defaults_for(media_type: str) -> list[str]:
    """视频默认原样保存（最快），音频默认 m4a（只抽音轨，不重编码）。"""
    return ["m4a"] if media_type == "audio" else ["source"]


def normalize(media_type: str, raw: list[str] | None) -> list[str]:
    """校验并去重；空就按媒体类型给默认值。"""
    allowed = AUDIO_FORMATS if media_type == "audio" else VIDEO_FORMATS
    picked = [f.strip().lower() for f in (raw or []) if isinstance(f, str) and f.strip()]
    if not picked:
        return defaults_for(media_type)
    out: list[str] = []
    for f in picked:
        if f not in allowed:
            raise ValueError(f"{f} 不是{'音频' if media_type == 'audio' else '视频'}可用的格式")
        if f not in out:
            out.append(f)
    return out[:4]


def ext_of(fmt: str, source_ext: str = "mp4") -> str:
    return FORMATS.get(fmt) or source_ext


def available() -> bool:
    return shutil.which(_binary()) is not None


def _binary() -> str:
    import os
    return os.environ.get("GETMEDIA_FFMPEG", "ffmpeg")


def convert(src: Path, dest: Path, fmt: str, *, timeout: float = 1800.0) -> None:
    """把 ``src`` 转成 ``fmt`` 写到 ``dest``；先试不重编码，失败再重编码。"""
    if fmt not in _ARGS:
        raise FormatError(f"不支持的格式：{fmt}")
    binary = shutil.which(_binary())
    if binary is None:
        raise FormatError("服务器没有安装 ffmpeg，无法转换格式")
    dest.parent.mkdir(parents=True, exist_ok=True)
    attempts = [_ARGS[fmt]]
    if fmt in _FALLBACK:
        attempts.append(_FALLBACK[fmt])
    last = ""
    for args in attempts:
        cmd = [binary, "-y", "-loglevel", "error", "-i", str(src), *args, str(dest)]
        try:
            proc = subprocess.run(cmd, capture_output=True, timeout=timeout, check=False)
        except subprocess.TimeoutExpired as exc:
            raise FormatError("转换超时") from exc
        if proc.returncode == 0 and dest.is_file() and dest.stat().st_size > 0:
            return
        last = (proc.stderr or b"").decode("utf-8", "replace").strip().splitlines()[-1:] or ["转换失败"]
        last = last[0][:160]
        dest.unlink(missing_ok=True)
    log.warning("格式转换失败（%s）：%s", fmt, last)
    raise FormatError(f"转成 {fmt.upper()} 失败：{last}")


__all__ = ["FORMATS", "VIDEO_FORMATS", "AUDIO_FORMATS", "FormatError", "normalize",
           "defaults_for", "ext_of", "available", "convert"]
