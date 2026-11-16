/** 5e：平台授权。
 *
 * 这里经手的只有"状态"和"会话号"：Cookie 文本只在提交那一刻穿过内存，提交后立刻从页面清掉，
 * 服务端也只回状态与数量（密文在库里，文件是 600 的 Netscape 格式）。
 * 保险箱不可用时整页只读并说明原因——没有密钥，导进来的 Cookie 也解不开。
 */
import { useEffect, useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';
import {
  ownerApi, type AuthSession, type Binding, type CredentialState, type OwnerConfig, type PlatformInfo,
} from '../api';
import { platformName } from '../labels';
import { ErrorBox, Field, fmtTime, Loading, relTime } from '../ui';

interface Data {
  platforms: PlatformInfo[];
  oauth: boolean;
  bindings: Binding[];
  creds: CredentialState[];
  cfg: OwnerConfig;
}

export function PlatformsPage() {
  const [data, setData] = useState<Data | null>(null);
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const [session, setSession] = useState<AuthSession | null>(null);
  const [cookie, setCookie] = useState('');
  const [note, setNote] = useState('');

  async function load() {
    setErr('');
    try {
      const [p, b, cfg] = await Promise.all([ownerApi.platforms(), ownerApi.bindings(), ownerApi.config()]);
      setData({ platforms: p.platforms, oauth: p.oauth_configured, bindings: b.bindings, creds: b.credentials, cfg });
    } catch (e) {
      setErr(messageFor(e));
    }
  }

  useEffect(() => {
    void load();
  }, []);

  const vaultOff = data ? !data.cfg.credential_vault : false;

  async function start(platform: string, method: 'webview' | 'cookie') {
    setBusy(true);
    setErr('');
    setNote('');
    setCookie('');
    try {
      const s = await ownerApi.startAuth(platform, method);
      setSession(s);
      setNote(
        method === 'webview'
          ? '会话已建。请到手机 App 里打开该平台的网页登录完成授权，然后回到这里点「检查状态」。'
          : '会话已建。把该平台的登录 Cookie 粘贴到下面再提交；提交成功后这段文本会立刻从页面清掉。',
      );
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function check() {
    if (!session) return;
    setBusy(true);
    setErr('');
    try {
      const s = await ownerApi.authStatus(session.session_id);
      setSession(s);
      setNote(
        s.status === 'done'
          ? '授权完成，绑定已更新。'
          : s.status === 'pending'
            ? '还在等待（会话 10 分钟内有效）。'
            : s.status === 'expired'
              ? '会话已过期，请重新发起。'
              : `当前状态：${s.status}`,
      );
      if (s.status === 'done') await load();
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function submit() {
    if (!session || !cookie.trim()) {
      setErr('先粘贴 Cookie');
      return;
    }
    if (!confirm('确认把这段 Cookie 提交给服务器保存？提交后页面会立刻清空这段文本。')) return;
    setBusy(true);
    setErr('');
    try {
      await ownerApi.submitCookie(session.session_id, cookie.trim());
      setCookie('');
      setNote('已保存。页面不再保留这段文本，服务端只回显状态。');
      setSession(null);
      await load();
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function cancel() {
    if (!session) return;
    setBusy(true);
    try {
      await ownerApi.cancelAuth(session.session_id);
      setSession(null);
      setCookie('');
      setNote('已取消这次授权会话。');
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function unbind(p: PlatformInfo) {
    if (!confirm(`解绑${p.name}？会删除服务器上的凭据与 Cookie 文件，媒体库不受影响。`)) return;
    setBusy(true);
    setErr('');
    try {
      await ownerApi.unbind(p.platform);
      setNote(`已解绑${p.name}。`);
      await load();
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  if (!data && !err) return <Loading what="平台授权状态" />;

  return (
    <>
      <h1>平台授权</h1>
      <p class="lead">
        服务器上的列表抓取与下载靠这里保存的登录态。没有授权时用游客身份，能看的更少、更容易被挡。
      </p>

      {err && <ErrorBox message={err} retry={() => void load()} />}

      {vaultOff && (
        <div class="alert warn">
          <strong>凭据保险箱不可用</strong>（服务器未配置 GETMEDIA_CRED_KEY）。这一页只能看状态：
          新的 Cookie 存不进去，已有的也解不开。需要先在服务器上配置密钥并重启 API。
        </div>
      )}

      {data && (
        <>
          <div class="alert info small">
            抖音开放平台 OAuth：{data.oauth ? '已配置' : '未配置（回调用不了，只能走网页登录或导入 Cookie）'}。
          </div>

          <ul class="jobs">
            {data.platforms.map((p) => {
              const b = data.bindings.find((x) => x.platform === p.platform);
              const c = data.creds.find((x) => x.platform === p.platform);
              return (
                <li class="job">
                  <div class="job-head">
                    <span class={`tag ${b ? 'ok' : ''}`}>{b ? '已绑定' : '未绑定'}</span>
                    <span class="grow ellipsis">{p.name}</span>
                    <span class="small muted nowrap">{p.platform}</span>
                  </div>
                  <div class="small muted">{p.hint}</div>

                  {b && (
                    <div class="kv-grid">
                      <Field label="绑定来源" value={b.source === 'cookie' ? '导入 Cookie' : '网页登录'} />
                      <Field label="绑定时间" value={`${fmtTime(b.bound_at)}（${relTime(b.bound_at)}）`} />
                      <Field label="凭据" value={c ? `${c.label} · ${c.status}` : '无记录'} />
                      <Field
                        label="能否解密"
                        value={c ? (c.decryptable ? '可以' : '解不开（密钥变了？需要重新授权）') : '—'}
                      />
                      {c && <Field label="凭据更新时间" value={fmtTime(c.updated_at)} />}
                    </div>
                  )}

                  <div class="row-actions">
                    <button type="button" class="btn btn-ghost small" disabled={busy || vaultOff}
                            onClick={() => void start(p.platform, 'webview')}>
                      手机网页登录授权
                    </button>
                    <button type="button" class="btn btn-ghost small" disabled={busy || vaultOff}
                            onClick={() => void start(p.platform, 'cookie')}>
                      粘贴 Cookie 授权
                    </button>
                    <button type="button" class="btn btn-ghost small" disabled={busy || !b}
                            onClick={() => void unbind(p)}>
                      解绑
                    </button>
                  </div>
                </li>
              );
            })}
          </ul>

          {session && (
            <div class="panel-card">
              <div class="job-head">
                <strong>{platformName(session.platform)} 授权会话</strong>
                <span class={`tag ${session.status === 'done' ? 'ok' : ''}`}>{session.status}</span>
                <span class="grow" />
                <span class="small muted">{session.session_id}</span>
              </div>
              <p class="small muted">
                会话 10 分钟内有效，到 {fmtTime(session.expires_at)} 失效。方式：
                {session.method === 'webview' ? '手机网页登录' : '导入 Cookie'}。
              </p>
              <div class="row-actions">
                <button type="button" class="btn small" disabled={busy} onClick={() => void check()}>检查状态</button>
                <button type="button" class="btn btn-ghost small" disabled={busy} onClick={() => void cancel()}>取消会话</button>
              </div>

              {session.method === 'cookie' && (
                <>
                  <label class="field">
                    <span>粘贴 Cookie（只在提交时经过内存，页面不保存）</span>
                    <textarea
                      rows={4}
                      maxLength={16384}
                      spellcheck={false}
                      autocomplete="off"
                      placeholder="SESSDATA=…; bili_jct=…; 或浏览器导出的多行形式"
                      value={cookie}
                      onInput={(e) => setCookie((e.target as HTMLTextAreaElement).value)}
                    />
                  </label>
                  <div class="row-actions">
                    <button type="button" class="btn small" disabled={busy || !cookie.trim()} onClick={() => void submit()}>
                      提交并保存
                    </button>
                    <span class="small muted">提交后这段文本会从页面清空。</span>
                  </div>
                  <p class="small muted">
                    B 站至少要 SESSDATA；抖音至少要 sessionid / sessionid_ss / ttwid 之一。缺了会被服务端拒绝。
                  </p>
                </>
              )}
            </div>
          )}

          {note && <div class="alert info small">{note}</div>}
        </>
      )}
    </>
  );
}
