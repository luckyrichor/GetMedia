"""博主批量模块的运行时配置。

GetMedia 的 ``Settings`` 是启动时构建的 frozen dataclass，不在模块之间传递；
平台适配器从旧音频项目移植过来时按 ``settings.xxx`` 取值，所以这里提供一个
轻量代理：启动时 ``configure(settings)`` 注入一次，其余模块照旧读属性。
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

_settings: Any = None


class _SettingsProxy:
    def __getattr__(self, name: str):
        if _settings is None:
            raise RuntimeError("博主批量配置未初始化（未调用 configure）")
        return getattr(_settings, name)

    def ensure_dirs(self) -> None:
        """确保 cookie 目录存在；权限收紧到 700。"""
        directory: Path = self.creator_cookie_dir
        directory.mkdir(parents=True, exist_ok=True)
        try:
            directory.chmod(0o700)
        except OSError:  # 非 POSIX 或只读挂载时不阻塞请求
            pass


settings = _SettingsProxy()


def configure(value: Any) -> None:
    global _settings
    _settings = value
