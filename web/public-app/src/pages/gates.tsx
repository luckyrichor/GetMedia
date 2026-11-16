/** 登录后、使用账号功能前必须先完成的两件事：重新同意协议（版本变更）、确认已保存恢复码。 */
import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { api, type Me } from '@shared/api';
import { messageFor } from '@shared/errors';
import { isApiError } from '@shared/errors';
import { useAsync } from '../hooks';
import { navigate } from '../router';
import { useSession } from '../session';
import { Alert, Button, Check, Page, Spinner } from '../ui';
import { ConsentLabel, RecoveryCodeBox } from './auth';

export function RequireAuth({ path, children }: { path: string; children: ComponentChildren }) {
  const s = useSession();
  const st = s.state;
  useEffect(() => {
    if (st.status === 'anon') navigate(`/login?next=${encodeURIComponent(path)}`, true);
  }, [st.status, path]);
  if (st.status === 'loading') return <Spinner />;
  if (st.status === 'anon') return null;
  if (st.me.consent_required) return <ConsentGate />;
  if (!st.me.recovery_confirmed) return <RecoveryGate me={st.me} />;
  return <>{children}</>;
}

function ConsentGate() {
  const s = useSession();
  const legal = useAsync(() => api.legal(), []);
  const [agree, setAgree] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: Event) {
    e.preventDefault();
    const d = legal.data;
    if (!d || !agree) return setErr('请先阅读并勾选同意');
    setBusy(true);
    setErr(null);
    try {
      await api.consent({
        user_agreement: { version: d.user_agreement.version, sha256: d.user_agreement.sha256 },
        privacy_policy: { version: d.privacy_policy.version, sha256: d.privacy_policy.sha256 },
      });
      await s.refresh();
    } catch (ex) {
      if (isApiError(ex, 'legal_outdated')) {
        setAgree(false);
        legal.reload();
        setErr('协议刚刚又更新了，请重新阅读后再勾选。');
      } else {
        setErr(s.fail(ex));
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <Page title="协议已更新" lead="继续使用前，请阅读并同意最新版本的《用户协议》和《隐私说明》。">
      <form onSubmit={submit}>
        {legal.data ? (
          <p class="hint">
            用户协议 v{legal.data.user_agreement.version}（{legal.data.user_agreement.effective_date} 生效）·
            隐私说明 v{legal.data.privacy_policy.version}（{legal.data.privacy_policy.effective_date} 生效）
          </p>
        ) : legal.error ? (
          <Alert>无法加载协议：{messageFor(legal.error)}</Alert>
        ) : (
          <Spinner />
        )}
        <Check checked={agree} onChange={setAgree}><ConsentLabel /></Check>
        {err ? <Alert>{err}</Alert> : null}
        <Button type="submit" busy={busy} disabled={!legal.data}>同意并继续</Button>
      </form>
      <p><button type="button" class="btn btn-link" onClick={() => void s.logout().then(() => navigate('/', true))}>不同意，退出登录</button></p>
    </Page>
  );
}

function RecoveryGate({ me }: { me: Me }) {
  const s = useSession();
  const pending = s.pendingRecovery && s.pendingRecovery.loginName === me.login_name ? s.pendingRecovery : null;
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function ack() {
    setBusy(true);
    setErr(null);
    try {
      await api.recoveryAck();
      s.setPendingRecovery(null);
      await s.refresh();
    } catch (ex) {
      setErr(s.fail(ex));
    } finally {
      setBusy(false);
    }
  }

  if (pending) {
    return (
      <Page title="请保存你的恢复码">
        <RecoveryCodeBox code={pending.code} />
        <Check checked={saved} onChange={setSaved}>我已把恢复码保存在安全的地方</Check>
        {err ? <Alert>{err}</Alert> : null}
        <Button onClick={ack} busy={busy} disabled={!saved}>继续</Button>
      </Page>
    );
  }
  // 页面被刷新或换了浏览器：恢复码只在生成时显示一次，已无法再显示
  return (
    <Page title="确认恢复码">
      <Alert kind="warn">
        你的账号还没有确认保存恢复码。恢复码只在生成的那一刻显示一次，现在已经无法再次显示。
        忘记密码时，恢复码是找回账号的唯一方式。
      </Alert>
      <p>如果你已经保存过，请点击确认；如果没有保存，也可以先继续使用，并通过「联系作者」说明情况。</p>
      {err ? <Alert>{err}</Alert> : null}
      <Button onClick={ack} busy={busy}>我已了解，继续</Button>
    </Page>
  );
}
