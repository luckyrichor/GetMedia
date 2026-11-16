"""AI 重建 worker（``getmedia-ai-worker.service`` 的入口）。

为什么单独一个进程：一次生成几分钟到几十分钟，放在 API 进程里会把 uvicorn 的
worker 拖死，也容易在滚动重启时丢掉半个产物。

本模块**不 import 任何模型客户端**：真正的执行函数由部署时通过
``GETMEDIA_AI_PLUGIN``（形如 ``包.模块:函数``）注入；没配置时把队列里的任务
标成 ``ai_unconfigured``，而不是悄悄失败或无限重试。

用法：
    python -m getmedia.ai_worker --once     # 处理一个任务后退出（便于 cron/测试）
    python -m getmedia.ai_worker            # 常驻
    python -m getmedia.ai_worker --status   # 打印队列深度与最近失败
"""
from __future__ import annotations

import argparse
import importlib
import logging
import os
import shutil
import sys
import time
from pathlib import Path

from .config import load_settings
from .db import make_engine, migrate
from .media import MediaStore
from .recreations import RecreationService

log = logging.getLogger("getmedia.ai_worker")


def load_runner(spec: str | None):
    """``pkg.mod:func`` → 可调用对象；空值返回 None（= 未配置模型）。"""
    if not spec:
        return None
    if ":" not in spec:
        raise SystemExit("GETMEDIA_AI_PLUGIN 必须形如 包.模块:函数")
    mod_name, _, func_name = spec.partition(":")
    mod = importlib.import_module(mod_name)
    return getattr(mod, func_name)


def build_service() -> RecreationService:
    settings = load_settings(os.environ)
    engine = make_engine(settings.database_path)
    migrate(engine, settings.database_path, settings.backup_dir, time.time())
    store = MediaStore(engine, settings, time.time)
    return RecreationService(engine, settings, time.time, media=store)


def run_once(service: RecreationService, runner=None) -> dict | None:
    job = service.claim()
    if job is None:
        return None
    settings = service.s
    if runner is None:
        service.finish(job["run_id"], "failed", error_code="ai_unconfigured")
        return {"run_id": job["run_id"], "status": "failed", "error": "ai_unconfigured"}

    # 磁盘保护：剩余空间不足就直接失败，不写半成品
    free_mb = shutil.disk_usage(settings.media_dir).free // 1024**2
    if free_mb < settings.ai_min_free_mb:
        service.finish(job["run_id"], "failed", error_code="no_space")
        return {"run_id": job["run_id"], "status": "failed", "error": "no_space"}

    rec_dir = Path(settings.media_dir) / "ai" / str(job["recreation_id"])
    rec_dir.mkdir(parents=True, exist_ok=True)
    try:
        out = runner(job["kind"], rec_dir)
        rel = Path(out).resolve().relative_to(Path(settings.media_dir).resolve())
        service.finish(job["run_id"], "done", output_path=str(rel))
        return {"run_id": job["run_id"], "status": "done"}
    except Exception as exc:  # noqa: BLE001 - 只记类型与简短消息，不记堆栈里的路径
        log.warning("AI 任务 %s 失败：%s", job["run_id"], type(exc).__name__)
        service.finish(job["run_id"], "failed", error_code=getattr(exc, "code", "ai_failed"))
        return {"run_id": job["run_id"], "status": "failed"}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="GetMedia AI 重建 worker")
    parser.add_argument("--once", action="store_true", help="处理一个任务后退出")
    parser.add_argument("--status", action="store_true", help="打印队列状态")
    parser.add_argument("--interval", type=float, default=5.0, help="空队列时的轮询间隔（秒）")
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    service = build_service()

    if args.status:
        with service.engine.begin() as c:
            from sqlalchemy import text
            rows = c.execute(text("SELECT status, COUNT(*) FROM recreation_runs GROUP BY status")).all()
        print({"queue": {r[0]: r[1] for r in rows}, "ai_enabled": service.s.ai_enabled})
        return 0

    service.reset_stale()
    runner = load_runner(os.environ.get("GETMEDIA_AI_PLUGIN"))
    if runner is None:
        log.warning("未配置 GETMEDIA_AI_PLUGIN：队列任务会被标记为 ai_unconfigured")
    if args.once:
        run_once(service, runner)
        return 0
    while True:
        did = run_once(service, runner)
        if did is None:
            time.sleep(args.interval)
    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
