"""管理 CLI（仅 SSH 使用）。WP3 提供 feedback 子命令；WP4 增补其余。
用法：python -m getmedia.cli <命令> ...（见 --help）
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


def _app(s: Settings):
    from .api import create_app
    return create_app(s)


def ops_cmd(s: Settings, args, out=sys.stdout) -> int:
    import getpass
    from pathlib import Path
    from .names import login_name_problem, normalize_login_name, password_problem
    from .security import new_id, new_recovery_code, recovery_hmac
    from .service import stat_day
    app = _app(s)
    svc, ops, eng = app.state.svc, app.state.ops, app.state.engine
    now = svc.now()
    cmd = args.cmd
    if cmd == "stats":
        with eng.begin() as c:
            total = c.execute(text("SELECT COUNT(*) FROM users")).scalar_one()
            print(f"注册总数：{total}", file=out)
            for d in (1, 7, 30):
                since = stat_day(now - (d - 1) * 86400)
                n = c.execute(text("SELECT COUNT(DISTINCT user_id) FROM heartbeats WHERE day>=:d"), {"d": since}).scalar_one()
                print(f"近 {d} 天活跃：{n}", file=out)
            for r in c.execute(text("SELECT channel,app_version,COUNT(DISTINCT user_id) FROM heartbeats WHERE day>=:d GROUP BY 1,2 ORDER BY 3 DESC"), {"d": stat_day(now - 29 * 86400)}):
                print(f"  {r[0]} {r[1] or '?'}：{r[2]}", file=out)
            for r in c.execute(text("SELECT day,registrations FROM daily_stats WHERE registrations>0 ORDER BY day DESC LIMIT 14")):
                print(f"  新增 {r[0]}：{r[1]}", file=out)
        return 0
    if cmd == "create-account":
        from .tiers import TIER_ADMIN, TIER_FREE, TIER_FULL
        norm = normalize_login_name(args.login_name)
        pw = args.password or getpass.getpass("密码：")
        p = login_name_problem(norm) or password_problem(pw, norm)
        if p:
            print(p, file=sys.stderr); return 1
        tier = TIER_ADMIN if args.admin else (TIER_FULL if (args.full or args.owner) else TIER_FREE)
        uid, code = new_id("acct"), new_recovery_code()
        h = svc.hasher.hash(pw)
        with eng.begin() as c:
            if c.execute(text("SELECT 1 FROM users WHERE login_name_norm=:n"), {"n": norm}).first():
                print("登录名已存在", file=sys.stderr); return 1
            if tier == TIER_ADMIN and c.execute(text("SELECT 1 FROM users WHERE tier='admin'"), {}).first():
                print("已存在管理员账号，全站只能有一个", file=sys.stderr); return 1
            c.execute(text("INSERT INTO users(id,login_name,login_name_norm,password_hash,tier,registered_channel,created_at) VALUES(:i,:n,:n,:p,:t,'cli',:c)"),
                      {"i": uid, "n": norm, "p": h, "t": tier, "c": now})
            c.execute(text("INSERT INTO recovery_codes(user_id,code_hmac,rotated_at) VALUES(:u,:h,:n)"), {"u": uid, "h": recovery_hmac(s.secret_key, code), "n": now})
            svc.audit(c, "cli_create", uid, "cli", "", tier)
        print(f"已创建 {norm}（{uid}），等级 {tier}\n恢复码（仅显示一次）：{code}", file=out)
        return 0
    if cmd == "set-tier":
        from .tiers import TIER_ADMIN, TIER_FREE, TIER_FULL
        from .service import add_months
        norm = normalize_login_name(args.login_name)
        with eng.begin() as c:
            r = c.execute(text("SELECT id,tier FROM users WHERE login_name_norm=:n"), {"n": norm}).first()
            if r is None:
                print("账号不存在", file=sys.stderr); return 1
            uid, old = r[0], r[1]
            if args.tier == TIER_ADMIN:
                if old != TIER_ADMIN and c.execute(text("SELECT 1 FROM users WHERE tier='admin'"), {}).first():
                    print("已存在管理员账号，全站只能有一个", file=sys.stderr); return 1
                exp = None
            elif args.tier == TIER_FULL:
                exp = add_months(now, args.months) if args.months else None
            else:
                exp = None
            c.execute(text("UPDATE users SET tier=:t, tier_expires_at=:e WHERE id=:u"),
                      {"t": args.tier, "e": exp, "u": uid})
            c.execute(text("UPDATE sessions SET revoked_at=:n WHERE user_id=:u AND revoked_at IS NULL"), {"n": now, "u": uid})
            svc.audit(c, f"cli_set_tier", uid, "cli", "", f"{old}->{args.tier}")
        print(f"{norm}：{old} → {args.tier}" + (f"，有效期至 {_ts(exp)}" if exp else ""), file=out)
        return 0
    if cmd == "sweep-tiers":
        n, m = svc.sweep_expired_tiers(), svc.sweep_invites()
        print(f"到期降级 {n} 个账号，清理邀请码 {m} 条", file=out)
        return 0
    if cmd in ("disable", "enable", "revoke-sessions", "set-owner", "unset-owner", "restore-account"):
        norm = normalize_login_name(args.login_name)
        with eng.begin() as c:
            r = c.execute(text("SELECT id FROM users WHERE login_name_norm=:n"), {"n": norm}).first()
            if r is None:
                print("账号不存在", file=sys.stderr); return 1
            uid = r[0]
            if cmd == "disable":
                c.execute(text("UPDATE users SET status='disabled' WHERE id=:u"), {"u": uid})
            if cmd in ("disable", "revoke-sessions"):
                c.execute(text("UPDATE sessions SET revoked_at=:n WHERE user_id=:u AND revoked_at IS NULL"), {"n": now, "u": uid})
            if cmd == "enable":
                c.execute(text("UPDATE users SET status='active' WHERE id=:u AND status='disabled'"), {"u": uid})
            if cmd in ("set-owner", "unset-owner"):
                # 旧命令保留为别名：owner 就是现在的 full
                c.execute(text("UPDATE users SET tier=:t, tier_expires_at=NULL WHERE id=:u"), {"t": "full" if cmd == "set-owner" else "free", "u": uid})
                c.execute(text("UPDATE sessions SET revoked_at=:n WHERE user_id=:u AND revoked_at IS NULL"), {"n": now, "u": uid})
            svc.audit(c, f"cli_{cmd}", uid, "cli", "", "ok")
        if cmd == "restore-account" and not ops.restore_account(uid):
            print("该账号没有待执行的注销请求", file=sys.stderr); return 1
        print("完成", file=out)
        return 0
    if cmd == "invite":
        codes = svc.create_invites(args.count, args.days)
        for code in codes:
            print(code, file=out)
        print(f"（{len(codes)} 个，{args.days} 天内有效；明文只显示这一次）", file=sys.stderr)
        return 0
    if cmd == "registration":
        rt = app.state.runtime.write(registration_open=args.state == "open")
        print(f"注册已{'开放' if rt.registration_open else '关闭'}", file=out)
        return 0
    if cmd == "invite-required":
        # 注册开放时是否仍要求邀请码（方案 §15-6："可先仅邀请码，再全面开放"）
        rt = app.state.runtime.write(invite_required=args.state == "on")
        print(f"邀请码要求已{'开启' if rt.invite_required else '关闭'}（当前注册{'开放' if rt.registration_open else '关闭'}）", file=out)
        return 0
    if cmd == "audit":
        with eng.begin() as c:
            for r in c.execute(text("SELECT ts,event,user_id,ip,path,result FROM audit ORDER BY id DESC LIMIT :n"), {"n": args.limit}):
                print(f"{_ts(r[0])} {r[1]:<16} {r[2] or '-':<22} {r[3]:<16} {r[4]} {r[5]}", file=out)
        return 0
    if cmd == "snapshot":
        p = ops.snapshot()
        print(f"快照：{p}" if p else "快照失败", file=out)
        return 0 if p else 1
    if cmd == "verify-backup":
        snaps = ops._snaps()
        bad = [p.name for p in snaps if not ops.verify(p)]
        print(f"快照 {len(snaps)} 份，校验失败 {len(bad)} 份{'：' + ','.join(bad) if bad else ''}", file=out)
        return 1 if bad or not snaps else 0
    if cmd == "restore":
        n = ops.restore(Path(args.snapshot), Path(args.target), args.replay_ledger)
        print(f"已恢复到 {args.target}；按台账重放删除 {n} 个账号", file=out)
        return 0
    if cmd == "alert":
        # 供部署脚本（证书续期失败等）发告警；与服务内告警共用去重：同类每天最多一封
        import re
        if not re.fullmatch(r"[a-z0-9_]{1,40}", args.kind):
            print("告警类型只能是小写字母、数字、下划线（最长 40 位）", file=sys.stderr); return 1
        app.state.alerts.notify(args.kind, args.detail[:500])
        print("已提交（同类告警每天最多一封）", file=out)
        return 0
    if cmd == "mail-test":
        # 邮件通道自检：成功只提示成功；失败只给异常类别与 SMTP 数字码，不输出地址或口令
        if s.mail is None:
            print("未配置邮件（FEEDBACK_* 七个变量不全）", file=sys.stderr); return 1
        from email.message import EmailMessage
        m = EmailMessage()
        m["From"], m["To"], m["Subject"] = s.mail.sender, s.mail.to, "[GetMedia 反馈] 邮件通道自检"
        m.set_content("这是管理命令发出的自检邮件。收到即说明发信通道可用。")
        try:
            app.state.fb.mailer(m)
        except Exception as e:  # noqa: BLE001
            # 代理段的失败没有 SMTP 数字码，补一个短原因（proxy_connect/proxy_auth/...），不含地址与口令
            print(f"发送失败：{type(e).__name__}  SMTP 码：{getattr(e, 'smtp_code', None)}"
                  + (f"  代理：{e.reason}" if getattr(e, "reason", "") else ""), file=sys.stderr)
            return 1
        print("发送成功（请到收件箱确认）", file=out)
        return 0
    if cmd == "ai-gc":
        # 保留期清理：只删过期项目的文件，记录保留（status=expired）。默认天数取配置。
        from .recreations import fmt_size
        r = app.state.recreations.gc(days=args.days or None, dry_run=args.dry_run)
        print(f"保留期 {r['days']} 天：{'待清理' if r['dry_run'] else '清理'} {r['projects']} 个项目，"
              f"释放 {fmt_size(r['freed_bytes'])}{'（演练，未删任何文件）' if r['dry_run'] else ''}", file=out)
        return 0
    if cmd == "tick":
        ops.tick(); print("完成", file=out); return 0
    return 2


def main(argv=None, settings: Settings | None = None, out=sys.stdout) -> int:
    p = argparse.ArgumentParser(prog="getmedia")
    sub = p.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("feedback")
    f.add_argument("action", choices=["list", "show", "export", "mark-handled"])
    f.add_argument("id", nargs="?", default="")
    f.add_argument("--all", action="store_true")
    f.add_argument("--limit", type=int, default=50)
    sub.add_parser("stats")
    ca = sub.add_parser("create-account"); ca.add_argument("login_name")
    ca.add_argument("--full", action="store_true", help="完整版")
    ca.add_argument("--admin", action="store_true", help="管理员（全站唯一）")
    ca.add_argument("--owner", action="store_true", help="--full 的旧写法")
    ca.add_argument("--password")
    st = sub.add_parser("set-tier"); st.add_argument("login_name"); st.add_argument("tier", choices=["free", "full", "admin"])
    st.add_argument("--months", type=int, default=0, help="完整版有效期（自然月，0=永久）")
    sub.add_parser("sweep-tiers")
    for name in ("disable", "enable", "revoke-sessions", "set-owner", "unset-owner", "restore-account"):
        sub.add_parser(name).add_argument("login_name")
    iv = sub.add_parser("invite")
    iv.add_argument("--count", type=int, default=1, help="生成个数（1–50，默认 1）")
    iv.add_argument("--days", type=int, default=1, help="有效期天数（默认 1）")
    rg = sub.add_parser("registration"); rg.add_argument("state", choices=["open", "close"])
    ir = sub.add_parser("invite-required"); ir.add_argument("state", choices=["on", "off"])
    au = sub.add_parser("audit"); au.add_argument("--limit", type=int, default=50)
    sub.add_parser("snapshot"); sub.add_parser("verify-backup"); sub.add_parser("tick"); sub.add_parser("mail-test")
    ag = sub.add_parser("ai-gc")
    ag.add_argument("--days", type=int, default=0, help="保留天数（默认取 GETMEDIA_AI_RETENTION_DAYS，14）")
    ag.add_argument("--dry-run", action="store_true", help="只统计释放量，不删文件、不改状态")
    al = sub.add_parser("alert"); al.add_argument("kind"); al.add_argument("detail", nargs="?", default="")
    rs = sub.add_parser("restore"); rs.add_argument("snapshot"); rs.add_argument("target"); rs.add_argument("--replay-ledger", action="store_true", default=True)
    args = p.parse_args(argv)
    s = settings or load_settings()
    if args.cmd == "feedback":
        if args.action in ("show", "mark-handled") and not args.id:
            p.error("需要反馈编号（前缀即可）")
        return feedback_cmd(s, args, out)
    return ops_cmd(s, args, out)


if __name__ == "__main__":
    sys.exit(main())
