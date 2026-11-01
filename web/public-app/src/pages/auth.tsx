import { useEffect, useState } from 'preact/hooks';
import { api, type Device } from '@shared/api';
import { clientInfo } from '@shared/clientinfo';
import { ApiError, isApiError, messageFor } from '@shared/errors';
import { formatDateTime } from '@shared/format';
import { formatRecoveryCode, loginNameProblem, normalizeLoginName, passwordProblem } from '@shared/validate';
import { useAsync } from '../hooks';
import { Link, href, navigate, safeNext } from '../router';
import { useSession } from '../session';
import { Alert, Button, Check, CopyButton, Field, Page, Spinner } from '../ui';

function useRedirectIfAuthed(to = '/account') {
  const s = useSession();
  useEffect(() => {
    if (s.state.status === 'authed') navigate(to, true);
  }, [s.state.status, to]);
}

/** 勾选语（方案决定 #11）：年龄确认与协议同意合为一项。 */
export function ConsentLabel() {
  return (
    <>
      我已年满 18 周岁，并已阅读并同意
      <a href={href('/legal/user-agreement')} target="_blank" rel="noopener noreferrer" data-legal="ua">《用户协议》</a>和
      <a href={href('/legal/privacy-policy')} target="_blank" rel="noopener noreferrer" data-legal="pp">《隐私说明》</a>
    </>
  );
}

export function Register() {
  const s = useSession();
  useRedirectIfAuthed();
  const legal = useAsync(() => api.legal(), []);
  const [name, setName] = useState('');
  const [pw, setPw] = useState('');
  const [pw2, setPw2] = useState('');
  const [invite, setInvite] = useState('');
  const [agree, setAgree] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [touched, setTouched] = useState(false);

  const cfg = s.config;
  const closed = cfg ? !cfg.registration_open : false;
  const needInvite = cfg ? closed || cfg.invite_required : false;

  const norm = normalizeLoginName(name);
  const nameErr = touched ? loginNameProblem(norm) : null;
  const pwErr = touched ? passwordProblem(pw, norm) : null;
  const pw2Err = touched && pw2 !== pw ? '两次输入的密码不一致' : null;

  async function submit(e: Event) {
    e.preventDefault();
    setTouched(true);
    setErr(null);
    if (loginNameProblem(norm) || passwordProblem(pw, norm) || pw !== pw2) return;
    if (needInvite && !invite.trim()) return setErr('当前需要邀请码才能注册，请填写邀请码');
    if (!agree) return setErr('请先阅读并勾选同意《用户协议》和《隐私说明》');
    const docs = legal.data;
    if (!docs) return setErr('协议文本还没有加载出来，请稍后再试');
    setBusy(true);
    try {
      const r = await api.register({
        login_name: norm,
        password: pw,
        invite_code: invite.trim() || undefined,
        age_confirmed: true,
        consent: {
          user_agreement: { version: docs.user_agreement.version, sha256: docs.user_agreement.sha256 },
          privacy_policy: { version: docs.privacy_policy.version, sha256: docs.privacy_policy.sha256 },
        },
        client: clientInfo(),
      });
      if (r.recovery_code) s.setPendingRecovery({ code: r.recovery_code, loginName: r.account.login_name });
      await s.refresh();
      navigate('/account', true);
    } catch (ex) {
      if (isApiError(ex, 'legal_outdated')) {
        setAgree(false);
        legal.reload();
        setErr('协议刚刚更新了。请重新阅读最新版本，并再次勾选同意。');
      } else {
        setErr(messageFor(ex));
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <Page title="注册账号" lead="账号用于登录、管理设备和联系作者。你的媒体文件只保存在自己的手机上。">
      {closed ? (
        <Alert kind="warn">
          目前未开放自由注册。如果你有邀请码，仍然可以在下面填写后注册。
        </Alert>
      ) : null}
      <form onSubmit={submit} noValidate>
        <Field label="登录名" value={name} onInput={setName} autoComplete="username" name="username" maxLength={64} required
          hint="3–32 位小写字母、数字、下划线或连字符；注册后不能修改" error={nameErr} />
        <Field label="密码" type="password" value={pw} onInput={setPw} autoComplete="new-password" name="new-password" maxLength={128} required
          hint="8–128 位，不能与登录名相同" error={pwErr} />
        <Field label="再次输入密码" type="password" value={pw2} onInput={setPw2} autoComplete="new-password" name="confirm-password" maxLength={128} required error={pw2Err} />
        {needInvite ? (
          <Field label="邀请码" value={invite} onInput={setInvite} autoComplete="off" name="invite" maxLength={64} required />
        ) : null}
        <Check checked={agree} onChange={setAgree}><ConsentLabel /></Check>
        {legal.data ? (
          <p class="hint" data-testid="legal-versions">
            用户协议 v{legal.data.user_agreement.version}（{legal.data.user_agreement.effective_date} 生效）·
            隐私说明 v{legal.data.privacy_policy.version}（{legal.data.privacy_policy.effective_date} 生效）
          </p>
        ) : legal.error ? (
          <Alert>无法加载协议文本：{messageFor(legal.error)} <button type="button" class="btn btn-link" onClick={legal.reload}>重试</button></Alert>
        ) : (
          <Spinner label="正在加载协议…" />
        )}
        {err ? <Alert>{err}</Alert> : null}
        <Button type="submit" busy={busy} disabled={!legal.data}>注册</Button>
      </form>
      <p class="muted">已有账号？<Link to="/login">去登录</Link></p>
    </Page>
  );
}

function devicesFrom(e: ApiError): Device[] {
  const d = e.extra['devices'];
  return Array.isArray(d) ? (d as Device[]) : [];
}

export function Login() {
  const s = useSession();
  const route = new URLSearchParams(location.search);
  const next = safeNext(route.get('next'));
  useRedirectIfAuthed(next ?? '/account');
  const [name, setName] = useState('');
  const [pw, setPw] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [full, setFull] = useState<Device[] | null>(null);
  const [evict, setEvict] = useState('');
  const setFlash = s.setFlash;
  useEffect(() => () => setFlash(null), [setFlash]);

  async function submit(e: Event) {
    e.preventDefault();
    setErr(null);
    if (!name.trim() || !pw) return setErr('请输入登录名和密码');
    if (full && !evict) return setErr('请选择一个要退出的设备');
    setBusy(true);
    try {
      await api.login({ login_name: normalizeLoginName(name), password: pw, client: clientInfo(), evict_device_id: evict || undefined });
      await s.refresh();
      navigate(next ?? '/account', true);
    } catch (ex) {
      if (isApiError(ex, 'device_limit')) {
        setFull(devicesFrom(ex as ApiError));
        setEvict('');
        setErr(null);
      } else {
        setErr(messageFor(ex));
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <Page title="登录">
      {s.flash ? <Alert kind={s.flash.kind}>{s.flash.text}</Alert> : null}
      <form onSubmit={submit} noValidate>
        <Field label="登录名" value={name} onInput={setName} autoComplete="username" name="username" maxLength={64} required />
        <Field label="密码" type="password" value={pw} onInput={setPw} autoComplete="current-password" name="password" maxLength={256} required />
        {full ? (
          <fieldset class="devices-pick">
            <legend>网页登录的设备已达上限，请选择一个退出后继续</legend>
            {full.map((d) => (
              <label class="radio" key={d.id}>
                <input type="radio" name="evict" value={d.id} checked={evict === d.id} onChange={() => setEvict(d.id)} />
                <span>
                  {d.device || '未命名设备'}
                  <small class="muted"> · 最近使用 {formatDateTime(d.last_used_at)}</small>
                </span>
              </label>
            ))}
          </fieldset>
        ) : null}
        {err ? <Alert>{err}</Alert> : null}
        <Button type="submit" busy={busy}>{full ? '退出所选设备并登录' : '登录'}</Button>
      </form>
      <p class="muted">
        <Link to="/recover">忘记密码？</Link> · <Link to="/register">注册新账号</Link>
      </p>
    </Page>
  );
}

export function Recover() {
  const s = useSession();
  useRedirectIfAuthed();
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [pw, setPw] = useState('');
  const [pw2, setPw2] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [touched, setTouched] = useState(false);
  const [done, setDone] = useState<string | null>(null);

  const norm = normalizeLoginName(name);
  const pwErr = touched ? passwordProblem(pw, norm) : null;
  const pw2Err = touched && pw2 !== pw ? '两次输入的密码不一致' : null;

  async function submit(e: Event) {
    e.preventDefault();
    setTouched(true);
    setErr(null);
    if (!norm || !code.trim()) return setErr('请输入登录名和恢复码');
    if (passwordProblem(pw, norm) || pw !== pw2) return;
    setBusy(true);
    try {
      const r = await api.recover(norm, code, pw);
      s.setPendingRecovery({ code: r.recovery_code, loginName: norm });
      setDone(r.recovery_code);
    } catch (ex) {
      setErr(messageFor(ex));
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <Page title="密码已重置">
        <Alert kind="ok">密码已重置，所有设备都已退出。旧恢复码已作废，下面是新的恢复码。</Alert>
        <RecoveryCodeBox code={done} />
        <Button onClick={() => navigate('/login')}>我已保存，去登录</Button>
      </Page>
    );
  }
  return (
    <Page title="找回账号" lead="用注册时保存的恢复码重置密码。重置后所有设备都会退出，并生成新的恢复码。">
      <form onSubmit={submit} noValidate>
        <Field label="登录名" value={name} onInput={setName} autoComplete="username" name="username" maxLength={64} required />
        <Field label="恢复码" value={code} onInput={setCode} autoComplete="off" name="recovery" maxLength={64} required hint="16 位，可带连字符或空格，不区分大小写" />
        <Field label="新密码" type="password" value={pw} onInput={setPw} autoComplete="new-password" name="new-password" maxLength={128} required error={pwErr} />
        <Field label="再次输入新密码" type="password" value={pw2} onInput={setPw2} autoComplete="new-password" name="confirm-password" maxLength={128} required error={pw2Err} />
        {err ? <Alert>{err}</Alert> : null}
        <Button type="submit" busy={busy}>重置密码</Button>
      </form>
      <p class="muted"><Link to="/login">想起来了，去登录</Link></p>
    </Page>
  );
}

/** 恢复码展示：只显示一次；不写入任何存储。 */
export function RecoveryCodeBox({ code }: { code: string }) {
  const shown = formatRecoveryCode(code);
  return (
    <div class="recovery-box">
      <p class="muted">恢复码只显示这一次，忘记密码时它是找回账号的唯一方式。请抄写、截图或复制到密码管理器。</p>
      <p class="recovery-code" data-testid="recovery-code"><code>{shown}</code></p>
      <CopyButton text={shown} label="复制恢复码" />
    </div>
  );
}
