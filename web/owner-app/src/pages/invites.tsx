/** 邀请码（仅管理员）：批量生成升级券，明文只显示一次，可下载 txt。
 *
 * 服务端只存 HMAC，所以**关闭面板后再也看不到明文**——面板上那句提示不是客套，
 * 是"关掉就真的没了"。生成不限每小时次数，只限制单次 1–50 个。
 *
 * 用途只有一种：把免费账号升成完整版 6 个自然月。没有"注册准入"这种选项。
 */
import { useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';
import { ownerApi, type InviteItem, type InviteState } from '../api';
import { Select } from '../select';
import { Empty, ErrorBox, Loading, NoAccess, fmtDateTime, useLoad } from '../ui';

const MIN = 1;
const MAX = 50;

const STATE_LABEL: Record<InviteState, string> = { unused: '未使用', used: '已使用', expired: '已过期' };
/** 未使用灰 / 已使用蓝 / 已过期红；红绿之外用蓝色，避免和"成功/失败"混淆 */
const STATE_CLASS: Record<InviteState, string> = { unused: 'tag', used: 'tag used', expired: 'tag bad' };

function download(codes: string[]): boolean {
  const text = codes.join('\n') + '\n';
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  const d = new Date();
  const two = (x: number) => String(x).padStart(2, '0');
  a.href = url;
  a.download = `getmedia-invites-${d.getFullYear()}${two(d.getMonth() + 1)}${two(d.getDate())}-${two(d.getHours())}${two(d.getMinutes())}.txt`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
  return true;
}

export function InvitesPage() {
  const me = useLoad(() => ownerApi.me());
  const [count, setCount] = useState('1');
  const [busy, setBusy] = useState(false);
  const [codes, setCodes] = useState<string[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [hint, setHint] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  // 换筛选/翻页保留旧列表（keep）：不抽掉表格换骨架，页面高度不变、不会跳回顶部
  const list = useLoad(() => ownerApi.adminInvites(status, page), [status, page], { keep: true });

  const n = Number(count);
  const bad = !Number.isInteger(n) || n < MIN || n > MAX;

  if (me.state.k === 'ok' && me.state.data.tier !== 'admin') return <NoAccess what="邀请码" />;

  async function generate() {
    if (bad) {
      setErr(`数量要在 ${MIN}–${MAX} 之间`);
      return;
    }
    setBusy(true);
    setErr(null);
    setHint('');
    try {
      const r = await ownerApi.adminCreateInvites(n);
      setCodes(r.codes);
      list.reload();
    } catch (e) {
      // 失败只在原位显示，不清空已填数量
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function copyAll() {
    if (!codes) return;
    try {
      await navigator.clipboard.writeText(codes.join('\n'));
      setHint('已复制到剪贴板');
    } catch {
      setErr('浏览器不允许写入剪贴板，请用「下载 .txt」');
    }
  }

  function save() {
    if (!codes) return;
    download(codes);
    setHint(`已下载 ${codes.length} 个邀请码`);
  }

  const data = list.state.k === 'ok' ? list.state.data : null;
  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;

  return (
    <>
      <h1>邀请码</h1>
      <div class="card">
        <div class="filter-row">
          <label class="small muted" for="inv-count">生成数量</label>
          <input id="inv-count" type="number" min={MIN} max={MAX} value={count} style="width:6rem"
                 onInput={(e) => setCount(e.currentTarget.value)} />
          <span class="small muted">{MIN}–{MAX}，默认 1</span>
          <button type="button" class="btn" disabled={busy || bad} onClick={() => void generate()}>生成</button>
        </div>
        {bad ? <p class="small bad-text">数量要是 {MIN}–{MAX} 之间的整数。</p> : null}
        {err ? <div class="alert warn">{err}</div> : null}
      </div>

      {codes ? (
        <div class="card">
          <h2>已生成 {codes.length} 个</h2>
          <div class="alert warn">
            只显示这一次。关闭后无法再查看，请现在保存或分发。
          </div>
          <div class="invite-result">
            <pre class="invite-codes">{codes.join('\n')}</pre>
            <div class="invite-actions">
              <button type="button" class="btn small" onClick={() => void copyAll()}>复制全部</button>
              <button type="button" class="btn small" onClick={save}>下载 .txt</button>
              <button type="button" class="btn btn-ghost small" onClick={() => { setCodes(null); setHint(''); }}>关闭</button>
            </div>
          </div>
        </div>
      ) : null}

      {hint ? <div class="alert info small">{hint}</div> : null}

      <div class="toolbar">
        <Select ariaLabel="按状态筛选" value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={[
          { value: '', label: '全部状态' }, { value: 'unused', label: '未使用' },
          { value: 'used', label: '已使用' }, { value: 'expired', label: '已过期' },
        ]} />
      </div>

      {list.state.k === 'loading' ? <Loading what="邀请码" /> : null}
      {list.state.k === 'error' ? <ErrorBox message={list.state.message} retry={list.reload} /> : null}
      {data && data.items.length === 0 ? (
        <Empty>{status ? '没有符合条件的邀请码。' : '还没有生成过邀请码。'}</Empty>
      ) : null}

      {data && data.items.length ? (
        <div class={list.refreshing ? 'table-wrap is-refreshing' : 'table-wrap'}>
          <table class="table">
            <thead>
              <tr><th>状态</th><th>创建时间</th><th>到期时间</th><th>使用人</th><th>使用时间</th></tr>
            </thead>
            <tbody>
              {data.items.map((i: InviteItem) => (
                <tr>
                  <td><span class={STATE_CLASS[i.state]}>{STATE_LABEL[i.state]}</span></td>
                  <td class="mono small">{fmtDateTime(i.created_at)}</td>
                  <td class="mono small">{fmtDateTime(i.expires_at)}</td>
                  <td>{i.used_by ?? '—'}</td>
                  <td class="mono small">{fmtDateTime(i.used_at)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {data && data.total > data.size ? (
        <div class="toolbar">
          <button type="button" class="btn small" disabled={page <= 1} onClick={() => setPage((p) => Math.max(1, p - 1))}>上一页</button>
          <span class="small muted">第 {data.page} / {pages} 页 · 共 {data.total} 条</span>
          <button type="button" class="btn small" disabled={page >= pages} onClick={() => setPage((p) => p + 1)}>下一页</button>
        </div>
      ) : null}
    </>
  );
}
