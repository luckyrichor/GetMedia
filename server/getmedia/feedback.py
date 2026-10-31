"""反馈：校验、图片处理、落库、后台发信队列、保留期清理。反馈库是权威记录，发信失败不影响用户。"""
from __future__ import annotations

import io
import json
import logging
import os
import re
import secrets
import smtplib
import ssl
import threading
import time
import uuid
from email.message import EmailMessage
from pathlib import Path

from PIL import Image, ImageOps
from sqlalchemy import text
from sqlalchemy.engine import Engine

from . import ratelimit
from .config import MailConfig, Settings
from .errors import ApiError

log = logging.getLogger("getmedia.feedback")

MAX_TEXT = 2000
MAX_CONTACT = 200
MAX_IMAGES = 3
MAX_IMAGE_BYTES = 5 * 1024 * 1024
MAX_TOTAL_BYTES = 12 * 1024 * 1024
MAX_PIXELS = 25_000_000
MAX_SIDE = 4096
CATEGORIES = {"bug", "suggestion", "account", "other"}
DIAG_KEYS = ("app_version", "os", "device", "channel", "network_type", "locale", "last_error_code")
_CTRL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
_BAD_DIAG = re.compile(r"cookie|token|password|authorization|session|https?://\S*\?", re.I)
_IDEM = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
_BACKOFF = (60, 300, 1800, 7200, 21600)
MAX_ATTEMPTS = len(_BACKOFF) + 1


def _clean_text(v: str, n: int) -> str:
    return _CTRL.sub("", v.replace("\r\n", "\n").replace("\r", "\n")).strip()[:n]


def filter_diagnostics(raw: str | None) -> dict:
    """白名单字段 + 二次过滤；解析失败或不是对象 → 空。"""
    if not raw:
        return {}
    try:
        data = json.loads(raw)
    except ValueError:
        return {}
    if not isinstance(data, dict):
        return {}
    out = {}
    for k in DIAG_KEYS:
        v = data.get(k)
        if isinstance(v, (str, int)) and not isinstance(v, bool):
            v = _clean_text(str(v), 128)
            if v and not _BAD_DIAG.search(v):
                out[k] = v
    return out


def sniff(b: bytes) -> str | None:
    if b[:3] == b"\xff\xd8\xff":
        return "JPEG"
    if b[:8] == b"\x89PNG\r\n\x1a\n":
        return "PNG"
    if b[:4] == b"RIFF" and b[8:12] == b"WEBP":
        return "WEBP"
    return None


def process_image(data: bytes) -> tuple[bytes, str]:
    """魔数 + 真实解码 + 像素上限 + 清除元数据 + 重新编码。返回 (字节, 扩展名)。"""
    if sniff(data) is None:
        raise ApiError(422, "bad_image", "只支持 JPEG、PNG、WebP 图片")
    try:
        with Image.open(io.BytesIO(data)) as im:
            w, h = im.size
            if w * h > MAX_PIXELS or w < 1 or h < 1:
                raise ApiError(422, "bad_image", "图片尺寸过大")
            im.load()
            im = ImageOps.exif_transpose(im)
            alpha = im.mode in ("RGBA", "LA", "P") and ("A" in im.getbands() or "transparency" in im.info)
            im = im.convert("RGBA" if alpha else "RGB")
            im.thumbnail((MAX_SIDE, MAX_SIDE))
            clean = Image.new(im.mode, im.size)  # 新建图像：不带任何元数据
            clean.paste(im)
            buf = io.BytesIO()
            if alpha:
                clean.save(buf, "PNG", optimize=True); ext = "png"
            else:
                clean.save(buf, "JPEG", quality=85, optimize=True); ext = "jpg"
            return buf.getvalue(), ext
    except ApiError:
        raise
    except Exception:  # noqa: BLE001 - 解码失败/炸弹/损坏：统一拒绝，不回显原因
        raise ApiError(422, "bad_image", "图片无法识别或已损坏") from None


class FeedbackService:
    def __init__(self, engine: Engine, settings: Settings, alerts, audit, clock=time.time):
        self.engine, self.s, self.alerts, self.audit, self.clock = engine, settings, alerts, audit, clock
        self.wake = threading.Event()

    def now(self) -> int:
        return int(self.clock())

    def _image_total(self, c) -> int:
        return c.execute(text("SELECT COALESCE(SUM(image_bytes),0) FROM feedback")).scalar_one()

    def submit(self, ctx, ip: str, *, text_: str, category: str, contact: str, diagnostics: str | None,
               idempotency_key: str, images: list[bytes]) -> str:
        if not _IDEM.match(idempotency_key or ""):
            raise ApiError(422, "validation_error", "提交的内容格式不正确")
        with self.engine.begin() as c:  # 幂等：同账号同 key 直接返回原结果，不计限流
            row = c.execute(text("SELECT id FROM feedback WHERE user_id=:u AND idempotency_key=:k"),
                            {"u": ctx.user_id, "k": idempotency_key}).first()
        if row:
            return row[0]
        body = _clean_text(text_ or "", MAX_TEXT + 1)
        if not body and not images:
            raise ApiError(422, "validation_error", "请填写反馈内容")
        if len(body) > MAX_TEXT:
            raise ApiError(422, "validation_error", "反馈内容过长")
        if len(images) > MAX_IMAGES or any(len(b) > MAX_IMAGE_BYTES for b in images) or sum(map(len, images)) > MAX_TOTAL_BYTES:
            raise ApiError(413, "too_large", "图片数量或大小超出限制")
        category = category if category in CATEGORIES else "other"
        contact = _clean_text(contact or "", MAX_CONTACT).replace("\n", " ")
        diag = filter_diagnostics(diagnostics)
        now = self.now()
        # 限流（独立短事务，被拒也计数）
        with self.engine.begin() as c:
            last = c.execute(text("SELECT MAX(created_at) FROM feedback WHERE user_id=:u"), {"u": ctx.user_id}).scalar_one()
            if last is not None and now - last < self.s.feedback_min_interval:
                raise ApiError(429, "rate_limited", "提交太频繁，请稍后再试",
                               headers={"Retry-After": str(self.s.feedback_min_interval - (now - last))})
            ok1, r1 = ratelimit.hit(c, f"fb:acct:{ctx.user_id}", *self.s.feedback_account_limit, now)
            ok2, r2 = ratelimit.hit(c, f"fb:ip:{ip}", *self.s.feedback_ip_limit, now)
        if not (ok1 and ok2):
            raise ApiError(429, "rate_limited", "今天提交的反馈已达上限，请明天再试", headers={"Retry-After": str(max(r1, r2))})
        processed = [process_image(b) for b in images]  # 先全部处理成功，再写盘
        new_bytes = sum(len(b) for b, _ in processed)
        fid = str(uuid.uuid4())
        paths: list[str] = []
        if processed:
            with self.engine.begin() as c:
                total = self._image_total(c)
            if total + new_bytes > self.s.feedback_image_total_cap:
                self.alerts.notify("feedback_images_full", "反馈图片总量已达上限，新图片被拒绝")
                raise ApiError(507, "storage_full", "暂时无法接收图片，请不带图片重试")
            d = self.s.feedback_image_dir
            d.mkdir(parents=True, exist_ok=True)
            d.chmod(0o700)
            for b, ext in processed:
                name = f"{secrets.token_hex(16)}.{ext}"
                p = d / name
                fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(fd, "wb") as f:
                    f.write(b)
                paths.append(name)
            if total + new_bytes > self.s.feedback_image_total_cap * 0.9:
                self.alerts.notify("feedback_images_near_cap", "反馈图片总量接近上限")
        try:
            with self.engine.begin() as c:
                c.execute(text(
                    "INSERT INTO feedback(id,user_id,created_at,category,text,contact,diagnostics_json,image_paths,image_bytes,idempotency_key) "
                    "VALUES(:i,:u,:t,:cat,:x,:co,:d,:p,:b,:k)"),
                    {"i": fid, "u": ctx.user_id, "t": now, "cat": category, "x": body, "co": contact,
                     "d": json.dumps(diag, ensure_ascii=False), "p": json.dumps(paths), "b": new_bytes, "k": idempotency_key})
                self.audit(c, "feedback", ctx.user_id, ip, "/v2/feedback", "ok")
        except Exception as e:  # noqa: BLE001
            for n in paths:
                (self.s.feedback_image_dir / n).unlink(missing_ok=True)
            if type(e).__name__ == "IntegrityError":  # 并发重复提交
                with self.engine.begin() as c:
                    row = c.execute(text("SELECT id FROM feedback WHERE user_id=:u AND idempotency_key=:k"),
                                    {"u": ctx.user_id, "k": idempotency_key}).first()
                if row:
                    return row[0]
            raise
        self.wake.set()
        return fid

    # ---------- 发信 ----------
    @staticmethod
    def build_message(cfg: MailConfig, row: dict) -> EmailMessage:
        def h(v: str) -> str:  # 头部字段：去掉一切换行/控制字符，防注入
            return re.sub(r"[\r\n\x00-\x1f\x7f]", " ", v)[:120]
        diag = json.loads(row["diagnostics_json"] or "{}")
        m = EmailMessage()
        m["From"], m["To"] = h(cfg.sender), h(cfg.to)
        m["Subject"] = h(f"[GetMedia 反馈] {row['id'][:8]} v{diag.get('app_version', '?')} {diag.get('channel', '?')}")
        lines = [f"编号：{row['id']}", f"类别：{row['category']}", f"时间：{row['created_at']}（Unix 秒）",
                 f"联系方式（用户自填，勿当作已验证）：{row['contact'] or '-'}", "", row["text"] or "(无文字)", "", "— 诊断 —"]
        lines += [f"{k}: {v}" for k, v in diag.items()] or ["(无)"]
        n = len(json.loads(row["image_paths"] or "[]"))
        lines += ["", f"图片：{n} 张（保存在服务器，用管理命令查看）"]
        m.set_content("\n".join(lines))
        return m

    def mailer(self, msg: EmailMessage) -> None:
        cfg = self.s.mail
        ctx = ssl.create_default_context()
        if cfg.security == "ssl":
            smtp = smtplib.SMTP_SSL(cfg.host, cfg.port, timeout=20, context=ctx)
        else:
            smtp = smtplib.SMTP(cfg.host, cfg.port, timeout=20)
            smtp.ehlo(); smtp.starttls(context=ctx)
        try:
            smtp.login(cfg.user, cfg.password)
            smtp.send_message(msg)
        finally:
            try:
                smtp.quit()
            except Exception:  # noqa: BLE001
                pass

    def process_queue(self, mailer=None) -> int:
        """处理到期的待发邮件；返回尝试次数。可重复调用；日上限超出的只入库不发。"""
        cfg = self.s.mail
        mailer = mailer or self.mailer
        now = self.now()
        with self.engine.begin() as c:
            rows = [dict(r._mapping) for r in c.execute(text(
                "SELECT * FROM feedback WHERE mail_state='pending' AND next_attempt_at<=:n ORDER BY created_at LIMIT 20"), {"n": now})]
        tried = 0
        for row in rows:
            if cfg is None:
                self._set_mail(row["id"], "skipped", row["email_attempts"], 0)
                continue
            with self.engine.begin() as c:
                ok, _ = ratelimit.hit(c, f"fb:mail:{now // 86400}", self.s.feedback_daily_mail_cap, 86400, now)
            if not ok:
                self._set_mail(row["id"], "skipped", row["email_attempts"], 0)
                continue
            tried += 1
            attempts = row["email_attempts"] + 1
            try:
                mailer(self.build_message(cfg, row))
                self._set_mail(row["id"], "sent", attempts, 0)
            except Exception as e:  # noqa: BLE001 - 只记类别与 SMTP 数字码，不记异常原文
                code = getattr(e, "smtp_code", None)
                log.warning("feedback mail failed: %s code=%s attempt=%s", type(e).__name__, code, attempts)
                if attempts >= MAX_ATTEMPTS:
                    self._set_mail(row["id"], "failed", attempts, 0)
                else:
                    self._set_mail(row["id"], "pending", attempts, now + _BACKOFF[attempts - 1])
        return tried

    def _set_mail(self, fid: str, state: str, attempts: int, nxt: int) -> None:
        with self.engine.begin() as c:
            c.execute(text("UPDATE feedback SET mail_state=:s,email_attempts=:a,next_attempt_at=:n WHERE id=:i"),
                      {"s": state, "a": attempts, "n": nxt, "i": fid})

    # ---------- 保留期 ----------
    def cleanup(self) -> dict:
        now = self.now()
        img_cut, txt_cut = now - self.s.feedback_image_days * 86400, now - self.s.feedback_text_days * 86400
        removed_img = 0
        with self.engine.begin() as c:
            old = c.execute(text("SELECT id,image_paths FROM feedback WHERE created_at<:t AND image_paths!='[]'"), {"t": img_cut}).all()
        for fid, paths in old:
            for n in json.loads(paths):
                (self.s.feedback_image_dir / Path(n).name).unlink(missing_ok=True); removed_img += 1
            with self.engine.begin() as c:
                c.execute(text("UPDATE feedback SET image_paths='[]', image_bytes=0 WHERE id=:i"), {"i": fid})
        with self.engine.begin() as c:
            rows = c.execute(text("SELECT id,image_paths FROM feedback WHERE created_at<:t"), {"t": txt_cut}).all()
            for fid, paths in rows:
                for n in json.loads(paths):
                    (self.s.feedback_image_dir / Path(n).name).unlink(missing_ok=True)
            removed_txt = c.execute(text("DELETE FROM feedback WHERE created_at<:t"), {"t": txt_cut}).rowcount
        return {"images": removed_img, "texts": removed_txt}

    def delete_for_user(self, c, user_id: str) -> None:
        """注销物理删除时调用（WP4）：删除该账号全部反馈文字与图片。"""
        for (paths,) in c.execute(text("SELECT image_paths FROM feedback WHERE user_id=:u"), {"u": user_id}).all():
            for n in json.loads(paths):
                (self.s.feedback_image_dir / Path(n).name).unlink(missing_ok=True)
        c.execute(text("DELETE FROM feedback WHERE user_id=:u"), {"u": user_id})

    # ---------- 后台线程 ----------
    def start_worker(self) -> threading.Thread:
        def loop():
            last_clean = 0.0
            while True:
                try:
                    self.process_queue()
                    if time.monotonic() - last_clean > 3600:
                        self.cleanup(); last_clean = time.monotonic()
                except Exception as e:  # noqa: BLE001
                    log.error("feedback worker error: %s", type(e).__name__)
                self.wake.wait(60); self.wake.clear()
        t = threading.Thread(target=loop, name="feedback-worker", daemon=True)
        t.start()
        return t
