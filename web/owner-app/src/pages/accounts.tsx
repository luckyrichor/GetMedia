/** 权限管理（仅管理员）：账号列表、改等级、重置密码、停用/恢复、撤销注销。
 *
 * 三条硬规则在界面上也要体现：
 * - 管理员行没有「设为…」「停用」，只有重置密码；
 * - 改等级 / 重置密码 / 停用都要吊销对方全部会话，确认文案里写明；
 * - 待注销行显示「还剩 N 天」与到秒的截止时间，方便判断来不来得及撤销。
 */
import { useEffect, useMemo, useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';
import { ownerApi, type AdminAccount, type AdminAccountPage, type Tier } from '../api';
import { Select } from '../select';
import { Empty, ErrorBox, Loading, NoAccess, fmtBytes, fmtDateTime, fmtTime, relTime, useLoad } from '../ui';

const TIER_LABEL: Record<Tier, string> = { free: '免费版', full: '完整版', admin: '管理员' };
const ALL_TIERS: Tier[] = ['free', 'full', 'admin'];
/** 表格列。带 `sort` 的列点击表头排序；表头与表体都按这张表来，列数不会对不上。 */
const COLS: { label: string; sort?: string }[] = [
  { label: '登录名', sort: 'login_name' },
  { label: '等级' },
  { label: '有效期', sort: 'expires_at' },
  { label: '状态' },
  { label: '创建时间', sort: 'created_at' },
  { label: '最近活跃', sort: 'last_seen_at' },
  { label: '媒体', sort: 'tracks' },
  { label: '列表 / 占用' },
  { label: '操作' },
];

function dayStart(s: string): number | undefined {
  if (!s) return undefined;
  const t = new Date(`${s}T00:00:00`).getTime();
  return Number.isFinite(t) ? Math.floor(t / 1000) : undefined;
}
function dayEnd(s: string): number | undefined {
  if (!s) return undefined;
  const t = new Date(`${s}T23:59:59`).getTime();
  return Number.isFinite(t) ? Math.floor(t / 1000) : undefined;
}

function expiryCell(a: AdminAccount) {
  if (a.admin) return <span class="muted">永久</span>;
  if (a.tier === 'free') return <span class="muted">—</span>;
  if (a.tier_expires_at == null) return <span>永久</span>;
  const now = Math.floor(Date.now() / 1000);
  if (a.tier_expires_at <= now) return <span class="tag bad">已过期</span>;
  const soon = a.tier_expires_at - now <= 30 * 86400;
  return <span class={soon ? 'tag warn' : ''}>{fmtTime(a.tier_expires_at)}</span>;
}

function statusCell(a: AdminAccount) {
  if (a.status === 'disabled') return <span class="tag warn-tag">已停用</span>;
  if (a.status === 'pending_deletion' && a.deletion) {
    return (
      <span class="tag bad">
        待注销 · 还剩 {a.deletion.days_left} 天 · 截止 {fmtDateTime(a.deletion.execute_at)}
      </span>
    );
  }
  return <span class="muted">正常</span>;
}

export function AccountsPage() {
  const me = useLoad(() => ownerApi.me());
  const [q, setQ] = useState('');
  const [debounced, setDebounced] = useState('');
  const [tiers, setTiers] = useState<Tier[]>(ALL_TIERS);
  const [status, setStatus] = useState('');
  const [expiry, setExpiry] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [hasData, setHasData] = useState('');
  const [sort, setSort] = useState('created_at');
  const [order, setOrder] = useState<'asc' | 'desc'>('desc');
  const [page, setPage] = useState(1);

  const [flash, setFlash] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);
  const [tierFor, setTierFor] = useState<AdminAccount | null>(null);
  const [tierMonths, setTierMonths] = useState<'6m' | 'permanent'>('6m');
  const [pwFor, setPwFor] = useState<AdminAccount | null>(null);
  const [pw1, setPw1] = useState('');
  const [pw2, setPw2] = useState('');
  const [busy, setBusy] = useState(false);

  // 输入停止 300ms 再查，避免每敲一个字符打一次接口
  useEffect(() => {
    const t = setTimeout(() => { setDebounced(q.trim()); setPage(1); }, 300);
    return () => clearTimeout(t);
  }, [q]);

  // 三个等级默认全选。全选等于不过滤，参数就不发，省得接口白拼一个 `tier=free,full,admin`
  const tierFilter = tiers.length === ALL_TIERS.length ? undefined : tiers;

  const query = useMemo(() => ({
    q: debounced || undefined,
    tier: tierFilter,
    status: status || undefined,
    expiry: expiry || undefined,
    created_from: dayStart(from),
    created_to: dayEnd(to),
    has_data: hasData === '' ? undefined : hasData === '1',
    sort,
    order,
    page,
  }), [debounced, tiers, status, expiry, from, to, hasData, sort, order, page]);

  // 条件写进 URL：刷新/收藏/换个标签页打开都还在
  useEffect(() => {
    const p = new URLSearchParams();
    if (debounced) p.set('q', debounced);
    if (tierFilter) p.set('tier', tierFilter.join(','));
    if (status) p.set('status', status);
    if (expiry) p.set('expiry', expiry);
    if (from) p.set('from', from);
    if (to) p.set('to', to);
    if (hasData) p.set('data', hasData);
    if (sort !== 'created_at') p.set('sort', sort);
    if (order !== 'desc') p.set('order', order);
    if (page > 1) p.set('page', String(page));
    const qs = p.toString();
    history.replaceState(null, '', qs ? `?${qs}` : location.pathname);
  }, [debounced, tiers, status, expiry, from, to, hasData, sort, order, page]);

  const { state, reload } = useLoad(() => ownerApi.adminAccounts(query), [JSON.stringify(query)]);

  if (me.state.k === 'ok' && me.state.data.tier !== 'admin') return <NoAccess what="权限管理" />;

  function toggleTier(t: Tier) {
    setPage(1);
    setTiers((cur) => {
      if (!cur.includes(t)) return [...cur, t];
      // 至少留一个等级，否则"什么都不选"和"全选"看起来一样、行为也一样，容易让人误以为筛选坏了
      if (cur.length === 1) return cur;
      return cur.filter((x) => x !== t);
    });
  }

  /** 点表头：同一列再点一次反序，换列则回到该列的默认方向（时间类倒序、名字正序）。 */
  function sortBy(col: string) {
    setPage(1);
    if (sort === col) {
      setOrder(order === 'asc' ? 'desc' : 'asc');
    } else {
      setSort(col);
      setOrder(col === 'login_name' ? 'asc' : 'desc');
    }
  }

  async function doTier(a: AdminAccount) {
    if (!tierFor) return;
    setBusy(true);
    try {
      const target: 'free' | 'full' = tierFor.tier === 'full' ? 'free' : 'full';
      const r = await ownerApi.adminSetTier(a.id, target, target === 'free' ? '6m' : tierMonths);
      setFlash({
        kind: 'ok',
        text: `${a.login_name} 已设为${TIER_LABEL[target]}`
          + (r.tier_expires_at ? `（有效期至 ${fmtTime(r.tier_expires_at)}）` : '')
          + `；该账号 ${r.revoked_sessions} 个登录设备已退出`,
      });
      setTierFor(null);
      reload();
    } catch (e) {
      setFlash({ kind: 'err', text: messageFor(e) });
    } finally {
      setBusy(false);
    }
  }

  async function doStatus(a: AdminAccount, next: 'disabled' | 'active') {
    const msg = next === 'disabled'
      ? `停用「${a.login_name}」？\n\n该账号立即无法登录（登录时会看到"该账号已被停用"），${a.active_sessions} 个登录设备全部退出；数据保留，可随时恢复。`
      : `恢复「${a.login_name}」？\n\n状态会回到正常，但旧会话不会恢复，用户必须重新登录。`;
    if (!confirm(msg)) return;
    setBusy(true);
    try {
      const r = await ownerApi.adminSetStatus(a.id, next);
      setFlash({
        kind: 'ok',
        text: next === 'disabled'
          ? `已停用 ${a.login_name}；${r.revoked_sessions} 个设备已退出`
          : `${a.login_name} 已恢复，用户需要重新登录`,
      });
      reload();
    } catch (e) {
      setFlash({ kind: 'err', text: messageFor(e) });
    } finally {
      setBusy(false);
    }
  }

  async function doRestore(a: AdminAccount) {
    if (!confirm(`撤销「${a.login_name}」的注销申请？\n\n账号恢复正常、数据保留；不撤销的话，${a.deletion ? fmtDateTime(a.deletion.execute_at) : '到期后'} 会永久删除。`)) return;
    setBusy(true);
    try {
      await ownerApi.adminRestoreDeletion(a.id);
      setFlash({ kind: 'ok', text: `已撤销 ${a.login_name} 的注销，账号恢复正常` });
      reload();
    } catch (e) {
      setFlash({ kind: 'err', text: messageFor(e) });
    } finally {
      setBusy(false);
    }
  }

  async function doPassword() {
    if (!pwFor) return;
    if (pw1.length < 8) return setFlash({ kind: 'err', text: '新密码至少 8 位' });
    if (pw1 !== pw2) return setFlash({ kind: 'err', text: '两次输入的新密码不一致' });
    setBusy(true);
    try {
      const r = await ownerApi.adminResetPassword(pwFor.id, pw1);
      setFlash({
        kind: 'ok',
        text: `已重置 ${pwFor.login_name} 的密码；该账号 ${r.revoked_sessions} 个登录设备已退出，必须用新密码重新登录`,
      });
      setPwFor(null);
      setPw1('');
      setPw2('');
      reload();
    } catch (e) {
      setFlash({ kind: 'err', text: messageFor(e) });
    } finally {
      setBusy(false);
    }
  }

  const data: AdminAccountPage | null = state.k === 'ok' ? state.data : null;
  const s = data?.summary;

  return (
    <>
      <h1>权限管理</h1>
      <p class="muted">
        等级：免费版 / 完整版（6 个月或永久）/ 管理员（全站唯一、永久）。改等级、重置密码、停用都会让该账号的所有设备退出登录。
      </p>

      {s ? (
        <div class="cards">
          <div class="card"><div class="muted small">账号总数</div><div class="stat">{data?.total ?? 0}</div></div>
          <div class="card"><div class="muted small">免费版</div><div class="stat">{s.free}</div></div>
          <div class="card"><div class="muted small">完整版</div><div class="stat">{s.full}</div></div>
          <div class="card"><div class="muted small">管理员</div><div class="stat">{s.admin}</div></div>
          <div class="card"><div class="muted small">30 天内到期</div><div class="stat">{s.expiring_30d}</div></div>
          <div class="card"><div class="muted small">已停用 / 待注销</div><div class="stat">{s.disabled} / {s.pending_deletion}</div></div>
        </div>
      ) : null}

      <div class="filters">
        <div class="filter-row">
          <input class="search" type="search" placeholder="搜索登录名" value={q} onInput={(e) => setQ(e.currentTarget.value)} />
          <div class="chips" role="group" aria-label="按等级筛选">
            {(['free', 'full', 'admin'] as Tier[]).map((t) => (
              <button type="button" class={tiers.includes(t) ? 'chip on' : 'chip'} onClick={() => toggleTier(t)}>
                {TIER_LABEL[t]}
              </button>
            ))}
          </div>
        </div>
        <div class="filter-row">
          <Select ariaLabel="按状态筛选" value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={[
            { value: '', label: '全部状态' }, { value: 'active', label: '正常' },
            { value: 'disabled', label: '已停用' }, { value: 'pending_deletion', label: '待注销' },
          ]} />
          <Select ariaLabel="按到期筛选" value={expiry} onChange={(v) => { setExpiry(v); setPage(1); }} options={[
            { value: '', label: '全部有效期' }, { value: 'permanent', label: '永久' },
            { value: 'soon', label: '30 天内到期' }, { value: 'expired', label: '已过期' },
          ]} />
          <Select ariaLabel="按数据量筛选" value={hasData} onChange={(v) => { setHasData(v); setPage(1); }} options={[
            { value: '', label: '有无数据都看' }, { value: '1', label: '有曲目' }, { value: '0', label: '无曲目' },
          ]} />
        </div>
        <div class="filter-row">
          <label class="small muted">创建时间</label>
          <input type="date" value={from} onInput={(e) => { setFrom(e.currentTarget.value); setPage(1); }} />
          <span class="muted">至</span>
          <input type="date" value={to} onInput={(e) => { setTo(e.currentTarget.value); setPage(1); }} />
        </div>
      </div>

      {flash ? <div class={flash.kind === 'ok' ? 'alert info' : 'alert warn'}>{flash.text}</div> : null}

      {tierFor ? (
        <div class="card">
          <h2>{tierFor.tier === 'full' ? `把「${tierFor.login_name}」设为免费版` : `把「${tierFor.login_name}」设为完整版`}</h2>
          {tierFor.tier === 'full' ? (
            <p class="muted">设为免费版后，该账号立即失去云端同步、服务器提取、批量提取等完整版能力，且所有设备退出登录。</p>
          ) : (
            <>
              <p class="muted">保存后该账号所有设备将退出登录，必须用原密码重新登录。</p>
              <div class="filter-row">
                <label><input type="radio" name="dur" checked={tierMonths === '6m'} onChange={() => setTierMonths('6m')} /> 6 个月</label>
                <label><input type="radio" name="dur" checked={tierMonths === 'permanent'} onChange={() => setTierMonths('permanent')} /> 永久</label>
              </div>
            </>
          )}
          <div class="filter-row">
            <button type="button" class="btn" disabled={busy} onClick={() => void doTier(tierFor)}>确认</button>
            <button type="button" class="btn btn-ghost" disabled={busy} onClick={() => setTierFor(null)}>取消</button>
          </div>
        </div>
      ) : null}

      {pwFor ? (
        <div class="card">
          <h2>重置「{pwFor.login_name}」的密码</h2>
          <p class="muted">保存后该账号所有设备都会退出登录，必须用新密码重新登录。密码不会显示在页面上。</p>
          <div class="filter-row">
            <input type="password" placeholder="新密码（8–128 位）" value={pw1} onInput={(e) => setPw1(e.currentTarget.value)} autoComplete="new-password" />
            <input type="password" placeholder="再次输入" value={pw2} onInput={(e) => setPw2(e.currentTarget.value)} autoComplete="new-password" />
          </div>
          <div class="filter-row">
            <button type="button" class="btn" disabled={busy} onClick={() => void doPassword()}>确认重置</button>
            <button type="button" class="btn btn-ghost" disabled={busy} onClick={() => { setPwFor(null); setPw1(''); setPw2(''); }}>取消</button>
          </div>
        </div>
      ) : null}

      {state.k === 'loading' ? <Loading what="账号列表" /> : null}
      {state.k === 'error' ? <ErrorBox message={state.message} retry={reload} /> : null}
      {data && data.items.length === 0 ? <Empty>没有符合条件的账号。</Empty> : null}

      {data && data.items.length ? (
        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                {COLS.map((c) => (
                  <th>
                    {c.sort ? (
                      <button type="button" class="th-sort" aria-pressed={sort === c.sort} onClick={() => sortBy(c.sort!)}>
                        {c.label}{sort === c.sort ? (order === 'asc' ? ' ↑' : ' ↓') : ''}
                      </button>
                    ) : c.label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {data.items.map((a) => (
                <tr>
                  <td>
                    {a.admin ? <span class="tag admin">管理员</span> : null}
                    <strong>{a.login_name}</strong>
                    <div class="muted small">{a.active_sessions} 台在线</div>
                  </td>
                  <td><span class={`tag tier-${a.tier}`}>{TIER_LABEL[a.tier]}</span></td>
                  <td>{expiryCell(a)}</td>
                  <td>{statusCell(a)}</td>
                  <td class="mono small">{fmtTime(a.created_at)}</td>
                  <td class="mono small">{a.last_seen_at ? relTime(a.last_seen_at) : '—'}</td>
                  <td class="small">音频 {a.audio} / 视频 {a.video} / 合计 {a.media_total}</td>
                  <td class="small">{a.playlists} 个 · {fmtBytes(a.bytes)}</td>
                  <td>
                    <div class="row-actions">
                      {a.admin ? null : a.status === 'disabled' ? (
                        <button type="button" class="btn small" disabled={busy} onClick={() => void doStatus(a, 'active')}>恢复</button>
                      ) : a.status === 'pending_deletion' ? (
                        <>
                          <button type="button" class="btn small" disabled={busy} onClick={() => void doRestore(a)}>撤销注销</button>
                          <button type="button" class="btn btn-ghost small" disabled={busy} onClick={() => void doStatus(a, 'disabled')}>停用</button>
                        </>
                      ) : (
                        <>
                          <button type="button" class="btn small" disabled={busy}
                                  onClick={() => { setTierFor(a); setTierMonths('6m'); }}>
                            {a.tier === 'full' ? '设为免费' : '设为完整版'}
                          </button>
                          <button type="button" class="btn btn-ghost small" disabled={busy} onClick={() => void doStatus(a, 'disabled')}>停用</button>
                        </>
                      )}
                      <button type="button" class="btn btn-ghost small" disabled={busy}
                              onClick={() => { setPwFor(a); setPw1(''); setPw2(''); }}>
                        重置密码
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {data && data.total > data.size ? (
        <div class="toolbar">
          <button type="button" class="btn small" disabled={page <= 1} onClick={() => setPage((p) => Math.max(1, p - 1))}>上一页</button>
          <span class="small muted">第 {data.page} / {Math.ceil(data.total / data.size)} 页 · 共 {data.total} 条</span>
          <button type="button" class="btn small" disabled={page >= Math.ceil(data.total / data.size)} onClick={() => setPage((p) => p + 1)}>下一页</button>
        </div>
      ) : null}
    </>
  );
}
