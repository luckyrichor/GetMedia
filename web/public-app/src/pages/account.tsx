import { useState } from 'preact/hooks';
import { api, type Device } from '@shared/api';
import { formatDateTime } from '@shared/format';
import { normalizeLoginName, passwordProblem } from '@shared/validate';
import { useAsync } from '../hooks';
import { Link, navigate } from '../router';
import { useSession } from '../session';
import { Alert, Button, Check, Field, Page, Spinner } from '../ui';

export function Account() {
  const s = useSession();
  const me = s.state.status === 'authed' ? s.state.me : null;
  if (!me) return null;
  return (
    <Page title="账号与安全">
      <div class="card">
        <p><strong>登录名</strong>：<span data-testid="login-name">{me.login_name}</span></p>
        <p class="muted">需要联系作者时，请使用「<Link to="/contact">联系作者</Link>」。</p>
      </div>
      <Devices />
      <ChangePassword />
      <div class="card">
        <h2>协议</h2>
        <p><Link to="/legal/user-agreement">用户协议</Link> · <Link to="/legal/privacy-policy">隐私说明</Link></p>
      </div>
      <div class="card">
        <h2>退出登录</h2>
        <Button variant="secondary" onClick={() => void s.logout().then(() => navigate('/', true))}>退出当前设备</Button>
      </div>
      <DeleteAccount />
    </Page>
  );
}

function Devices() {
  const s = useSession();
  const list = useAsync<Device[]>(() => api.devices(), []);
  const [busy, setBusy] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const others = (list.data ?? []).filter((d) => !d.current);

  async function run(key: string, fn: () => Promise<void>) {
    setBusy(key);
    setErr(null);
    setNote(null);
    try {
      await fn();
      list.reload();
    } catch (ex) {
      setErr(s.fail(ex));
    } finally {
      setBusy(null);
    }
  }

  return (
    <div class="card">
      <h2>登录的设备</h2>
      <p class="muted">手机应用最多 5 台，网页最多 3 个。看到不认识的设备，请退出它并修改密码。</p>
      {list.loading && !list.data ? <Spinner /> : null}
      {list.error ? <Alert>{s.fail(list.error)}</Alert> : null}
      {list.data ? (
        <ul class="devices">
          {list.data.map((d) => (
            <li key={d.id}>
              <div>
                <strong>{d.device || '未命名设备'}</strong>
                {d.current ? <span class="badge">当前设备</span> : null}
                <div class="muted small">
                  {d.channel === 'web' ? '网页' : '手机应用'}{d.app_version ? ` ${d.app_version}` : ''} · 登录于 {formatDateTime(d.created_at)} · 最近使用 {formatDateTime(d.last_used_at)}
                </div>
              </div>
              {!d.current ? (
                <Button variant="secondary" busy={busy === d.id} onClick={() => {
                  if (confirm('确定让这台设备退出登录吗？')) void run(d.id, async () => { await api.revokeDevice(d.id); setNote('已退出该设备'); });
                }}>退出</Button>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}
      {others.length > 0 ? (
        <Button variant="secondary" busy={busy === 'others'} onClick={() => {
          if (confirm(`确定退出其他 ${others.length} 台设备吗？当前设备不受影响。`)) {
            void run('others', async () => { const r = await api.revokeOthers(); setNote(`已退出其他 ${r.revoked} 台设备`); });
          }
        }}>退出其他所有设备</Button>
      ) : null}
      {note ? <Alert kind="ok">{note}</Alert> : null}
      {err ? <Alert>{err}</Alert> : null}
    </div>
  );
}

function ChangePassword() {
  const s = useSession();
  const login = s.state.status === 'authed' ? s.state.me.login_name : '';
  const [oldPw, setOld] = useState('');
  const [pw, setPw] = useState('');
  const [pw2, setPw2] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [ok, setOk] = useState(false);
  const [touched, setTouched] = useState(false);
  const pwErr = touched ? passwordProblem(pw, normalizeLoginName(login)) : null;
  const pw2Err = touched && pw !== pw2 ? '两次输入的密码不一致' : null;

  async function submit(e: Event) {
    e.preventDefault();
    setTouched(true);
    setErr(null);
    setOk(false);
    if (!oldPw) return setErr('请输入当前密码');
    if (passwordProblem(pw, normalizeLoginName(login)) || pw !== pw2) return;
    setBusy(true);
    try {
      await api.changePassword(oldPw, pw);
      setOk(true);
      setOld('');
      setPw('');
      setPw2('');
      setTouched(false);
    } catch (ex) {
      setErr(s.fail(ex));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form class="card" onSubmit={submit} noValidate>
      <h2>修改密码</h2>
      <p class="muted">修改成功后，其他设备会退出登录，当前设备保持登录。</p>
      <input type="text" name="username" value={login} autoComplete="username" readOnly hidden aria-hidden="true" tabIndex={-1} />
      <Field label="当前密码" type="password" value={oldPw} onInput={setOld} autoComplete="current-password" name="current-password" maxLength={256} />
      <Field label="新密码" type="password" value={pw} onInput={setPw} autoComplete="new-password" name="new-password" maxLength={128} error={pwErr} hint="8–128 位，不能与登录名相同" />
      <Field label="再次输入新密码" type="password" value={pw2} onInput={setPw2} autoComplete="new-password" name="confirm-password" maxLength={128} error={pw2Err} />
      {ok ? <Alert kind="ok">密码已修改，其他设备已退出登录。</Alert> : null}
      {err ? <Alert>{err}</Alert> : null}
      <Button type="submit" busy={busy}>修改密码</Button>
    </form>
  );
}

function DeleteAccount() {
  const s = useSession();
  const [pw, setPw] = useState('');
  const [sure, setSure] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: Event) {
    e.preventDefault();
    setErr(null);
    if (!pw) return setErr('请输入密码确认');
    if (!sure) return setErr('请先勾选确认');
    setBusy(true);
    try {
      const r = await api.deleteAccount(pw);
      s.setPendingRecovery(null);
      s.setFlash({
        kind: 'ok',
        text: `注销申请已提交。账号已停用并退出所有设备；${formatDateTime(r.delete_at)} 之后，账号与反馈内容会被永久删除。冷静期内如需恢复，只能由运营者处理。`,
      });
      await s.refresh();
      navigate('/login', true);
    } catch (ex) {
      setErr(s.fail(ex));
    } finally {
      setBusy(false);
    }
  }

  return (
    <details class="card danger-zone">
      <summary>注销账号</summary>
      <form onSubmit={submit} noValidate>
        <p>
          提交后账号立即停用并退出所有设备，<strong>7 天冷静期</strong>结束后，账号、登录设备记录和你提交过的反馈（含图片）会被永久删除，无法恢复。
          冷静期内如需撤销，只能由运营者处理。你保存在手机里的媒体文件不受影响。
        </p>
        <input type="text" name="username" value={s.state.status === 'authed' ? s.state.me.login_name : ''} autoComplete="username" readOnly hidden aria-hidden="true" tabIndex={-1} />
        <Field label="输入密码确认" type="password" value={pw} onInput={setPw} autoComplete="current-password" name="current-password" maxLength={256} />
        <Check checked={sure} onChange={setSure}>我了解注销后数据将被永久删除</Check>
        {err ? <Alert>{err}</Alert> : null}
        <Button type="submit" variant="danger" busy={busy}>注销账号</Button>
      </form>
    </details>
  );
}
