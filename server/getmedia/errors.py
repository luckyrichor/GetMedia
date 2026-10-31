from __future__ import annotations


class ApiError(Exception):
    """面向用户的错误：detail 为固定中文文案（不回显输入），code 为稳定机器码。"""

    def __init__(self, status: int, code: str, detail: str, headers: dict | None = None, extra: dict | None = None):
        super().__init__(code)
        self.status, self.code, self.detail = status, code, detail
        self.headers, self.extra = headers or {}, extra or {}
