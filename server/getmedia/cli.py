"""管理 CLI（仅 SSH 使用）。WP3 提供 feedback 子命令；WP4 增补其余。
用法：python -m getmedia.cli feedback list|show <id>|export|mark-handled <id>
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from datetime import datetime, timedelta, timezone

from sqlalchemy import text

from .config import Settings, load_settings
from .db import make_engine


def _ts(t: int) -> str:
    return (datetime.fromtimestamp(t, timezone.utc) + timedelta(hours=8)).strftime("%Y-%m-%d %H:%M")


def feedback_cmd(s: Settings, args, out=sys.stdout) -> int:
    eng = make_engine(s.database_path)
    with eng.begin() as c:
        if args.action == "list":
            q = "SELECT id,created_at,category,status,mail_state,substr(replace(text,char(10),' '),1,40) FROM feedback"
            if not args.all:
                q += " WHERE status='new'"
            for r in c.execute(text(q + " ORDER BY created_at DESC LIMIT :n"), {"n": args.limit}):
                print(f"{r[0][:8]}  {_ts(r[1])}  {r[2]:<10} {r[3]:<8} mail={r[4]:<8} {r[5]}", file=out)
            return 0
        if args.action == "export":
            w = csv.writer(out)
            w.writerow(["id", "time", "category", "status", "mail_state", "contact", "text", "diagnostics", "images"])
            for r in c.execute(text("SELECT id,created_at,category,status,mail_state,contact,text,diagnostics_json,image_paths FROM feedback ORDER BY created_at")):
                imgs = [str(s.feedback_image_dir / n) for n in json.loads(r[8])]
                w.writerow([r[0], _ts(r[1]), r[2], r[3], r[4], r[5], r[6], r[7], ";".join(imgs)])
            return 0
        rows = c.execute(text("SELECT id FROM feedback WHERE id LIKE :p"), {"p": args.id + "%"}).all()
        if len(rows) != 1:
            print("未找到唯一匹配的反馈编号", file=sys.stderr)
            return 1
        fid = rows[0][0]
        if args.action == "mark-handled":
            c.execute(text("UPDATE feedback SET status='handled' WHERE id=:i"), {"i": fid})
            print("已标记为已处理", file=out)
            return 0
        r = c.execute(text("SELECT * FROM feedback WHERE id=:i"), {"i": fid}).one()._mapping
        print(f"编号：{r['id']}\n时间：{_ts(r['created_at'])}\n账号：{r['user_id']}\n类别：{r['category']}  状态：{r['status']}  "
              f"邮件：{r['mail_state']}（尝试 {r['email_attempts']} 次）\n联系方式：{r['contact'] or '-'}\n诊断：{r['diagnostics_json']}", file=out)
        for n in json.loads(r["image_paths"]):
            print(f"图片：{s.feedback_image_dir / n}", file=out)
        print(f"\n{r['text']}", file=out)
        return 0


def main(argv=None, settings: Settings | None = None, out=sys.stdout) -> int:
    p = argparse.ArgumentParser(prog="getmedia")
    sub = p.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("feedback")
    f.add_argument("action", choices=["list", "show", "export", "mark-handled"])
    f.add_argument("id", nargs="?", default="")
    f.add_argument("--all", action="store_true")
    f.add_argument("--limit", type=int, default=50)
    args = p.parse_args(argv)
    s = settings or load_settings()
    if args.cmd == "feedback":
        if args.action in ("show", "mark-handled") and not args.id:
            p.error("需要反馈编号（前缀即可）")
        return feedback_cmd(s, args, out)
    return 2


if __name__ == "__main__":
    sys.exit(main())
