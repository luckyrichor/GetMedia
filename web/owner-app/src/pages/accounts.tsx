/** 权限管理（仅管理员）：账号列表、改等级、改有效期、重置密码、停用/恢复、撤销注销。
 *
 * 页面自上而下：标题 → 统计条（5 张等宽卡）→ 筛选区（搜索 + 等级分段 / 状态下拉 + 有效期 +
 * 数据 + 创建时间范围 + 重置）→ 表格卡片（固定列宽、单行、操作列不折行）→ 分页。
 *
 * 几条硬规则在界面上也要体现：
 * - 管理员行只有「重置密码」，没有改等级 / 停用 / 改有效期；
 * - 改等级、重置密码、停用都要吊销对方全部会话，确认文案里写明；
 * - 改有效期不踢人（等级每次请求都按库里的 `tier + tier_expires_at` 实时算）；
 * - 待注销的精确截止时间放进 `title`，列里只留「还剩 N 天」，保证整行单行显示。
 */
import { useEffect, useMemo, useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';
import { ownerApi, type AdminAccount, type AdminAccountPage, type Tier } from '../api';
import { Select } from '../select';
import {
  DateInput, DateTimeCell, ErrorBox, Loading, Menu, Modal, NoAccess, Segmented,
  fmtBytesParts, fmtDateTime, fmtTime, relTime, useLoad,
} from '../ui';

const TIER_LABEL: Record<Tier, string> = { free: '免费版', full: '完整版', admin: '管理员' };

/** 表格列。带 `sort` 的列点击表头排序。
 *
 *  列宽由 styles.css 里的 `--c-xxx` 变量给，`<col style="width: var(--c-xxx)">` 引用它：
 *  这样 1440 屏和 1280 屏能有两套（数字列宽屏 80px、窄屏 48px）。
 *  （试过 `<col class>` + `col.c-xxx{width}`：Chrome 把后面几列都塌到同一个 x 上，不能用；
 *   只给 `<th>` 设宽度也不稳，表格会整块崩掉。）
 *
 *  四个数字列不排序：服务端只有 `tracks`（媒体总数）一个键，拆成音频/视频/列表数/占用之后
 *  没有哪一列对得上它，挂上去反而会误导。
 */
const COLS: { label: string; sort?: string; cls: string; mid?: boolean }[] = [
  { label: '登录名', sort: 'login_name', cls: 'c-name' },
  { label: '等级', cls: 'c-tier' },
  { label: '有效期', sort: 'expires_at', cls: 'c-exp' },
  { label: '状态', cls: 'c-status' },
  { label: '创建时间', sort: 'created_at', cls: 'c-created' },
  { label: '最近活跃', sort: 'last_seen_at', cls: 'c-active' },
  { label: '音频', cls: 'c-num', mid: true },
  { label: '视频', cls: 'c-num', mid: true },
  { label: '列表数', cls: 'c-num', mid: true },
  { label: '占用', cls: 'c-bytes', mid: true },
  { label: '操作', cls: 'c-ops' },
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

/** 本地时区的 `YYYY-MM-DD`，也就是 `input[type=date]` 的取值格式。
 *  日期框、表格里的创建时间、`dayStart/dayEnd` 三者都按本地时区解释，口径一致。 */
function ymd(d: Date): string {
  const two = (x: number) => String(x).padStart(2, '0');
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}`;
}
function daysAgo(n: number): Date {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return d;
}

/** epoch 秒 → `<input type="datetime-local" step="1">` 认的字符串（本地时间，不带时区）。 */
function toLocalInput(unix: number): string {
  const d = new Date(unix * 1000);
  const two = (x: number) => String(x).padStart(2, '0');
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}`
    + `T${two(d.getHours())}:${two(d.getMinutes())}:${two(d.getSeconds())}`;
}

/** datetime-local 的字符串按本地时区解析（不带时区的日期时间就是本地时间），再换成 epoch 秒。 */
function fromLocalInput(s: string): number | undefined {
  if (!s) return undefined;
  const t = new Date(s).getTime();
  return Number.isFinite(t) ? Math.floor(t / 1000) : undefined;
}

/** 加自然月，日号超出目标月天数时收敛到月末（8-31 +6 → 2-28）——与服务端 `add_months` 一致。 */
function addMonthsTs(unix: number, months: number): number {
  const d = new Date(unix * 1000);
  const y = d.getFullYear() + Math.floor((d.getMonth() + months) / 12);
  const m = ((d.getMonth() + months) % 12 + 12) % 12;
  const last = new Date(y, m + 1, 0).getDate();
  const c = new Date(d);
  c.setFullYear(y, m, Math.min(d.getDate(), last));
  return Math.floor(c.getTime() / 1000);
}

/** 日期时间两段式（日期 + 精确到秒的时分秒）由 ui.tsx 的 `DateTimeCell` 统一提供，
 *  邀请码页也用同一个，两页的时间看起来才是一套东西。 */
const dtCell = (unix: number, cls = '') => <DateTimeCell at={unix} cls={cls} />;

function expiryCell(a: AdminAccount) {
  // 管理员的有效期没有含义（永远是管理员），留一道横线，别写「永久」让人以为也能改
  if (a.admin) return <span class="muted">—</span>;
  if (a.tier === 'free') return <span class="muted">—</span>;
  if (a.tier_expires_at == null) return <span class="perm">永久</span>;
  const now = Math.floor(Date.now() / 1000);
  if (a.tier_expires_at <= now) return <span class="text-bad">已过期</span>;
  const soon = a.tier_expires_at - now <= 30 * 86400;
  return dtCell(a.tier_expires_at, soon ? 'text-warn' : '');
}

function statusCell(a: AdminAccount) {
  if (a.status === 'disabled') {
    return <span class="status status-warn"><i class="dot" aria-hidden="true" />已停用</span>;
  }
  if (a.status === 'pending_deletion' && a.deletion) {
    // 精确到秒的截止时间放 title：列里塞下「还剩 N 天 · 截止 …」必然折行
    return (
      <span class="status status-bad" title={`截止 ${fmtDateTime(a.deletion.execute_at)}`}>
        <i class="dot" aria-hidden="true" />待注销 · {a.deletion.days_left} 天
      </span>
    );
  }
  return <span class="status status-ok"><i class="dot" aria-hidden="true" />正常</span>;
}

/** 最近活跃：一律相对时间。`relTime` 超过 30 天会回落到「2026-11-02 07:25」，
 *  那一串比「45 天前」宽一倍，会把这一列撑开；这里只保留相对口径。 */
function activeText(unix: number | null): string {
  if (!unix) return '—';
  const days = Math.floor((Date.now() / 1000 - unix) / 86400);
  return days >= 30 ? `${days} 天前` : relTime(unix);
}

/** 占用：数字和单位作为**一个整体**在列里居中。
 *
 *  外面这层是固定宽度的 inline-grid：左格数字右对齐、右格单位左对齐且宽度固定——
 *  不这么拆，「229 MB」和「0 B」的数字右边缘就对不齐（数字位数不同，单位会被顶来顶去）。
 *  容器本身居中，所以整列的视觉中心落在"数字+单位"这个整体上，而不是贴着单元格右边缘。
 */
function byteCell(n: number) {
  const p = fmtBytesParts(n);
  return (
    <span class="bytes">
      <span class="bytes-n">{p.num}</span>
      <span class="bytes-u">{p.unit}</span>
    </span>
  );
}

export function AccountsPage() {
  const me = useLoad(() => ownerApi.me());
  const [q, setQ] = useState('');
  const [debounced, setDebounced] = useState('');
  /** 等级：`''` = 全部。分段控件是单选，接口仍收数组。 */
  const [tier, setTier] = useState<'' | Tier>('');
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
  const [expFor, setExpFor] = useState<AdminAccount | null>(null);
  /** 输入框里的本地时间串（`YYYY-MM-DDTHH:mm:ss`）；换算成 epoch 再发给服务端 */
  const [expValue, setExpValue] = useState('');
  const [expPermanent, setExpPermanent] = useState(false);
  const [pwFor, setPwFor] = useState<AdminAccount | null>(null);
  const [pw1, setPw1] = useState('');
  const [pw2, setPw2] = useState('');
  const [busy, setBusy] = useState(false);

  // 输入停止 300ms 再查，避免每敲一个字符打一次接口
  useEffect(() => {
    const t = setTimeout(() => { setDebounced(q.trim()); setPage(1); }, 300);
    return () => clearTimeout(t);
  }, [q]);

  const query = useMemo(() => ({
    q: debounced || undefined,
    tier: tier ? [tier] : undefined,
    status: status || undefined,
    expiry: expiry || undefined,
    created_from: dayStart(from),
    created_to: dayEnd(to),
    has_data: hasData === '' ? undefined : hasData === '1',
    sort,
    order,
    page,
  }), [debounced, tier, status, expiry, from, to, hasData, sort, order, page]);

  // 条件写进 URL：刷新/收藏/换个标签页打开都还在
  useEffect(() => {
    const p = new URLSearchParams();
    if (debounced) p.set('q', debounced);
    if (tier) p.set('tier', tier);
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
  }, [debounced, tier, status, expiry, from, to, hasData, sort, order, page]);

  // 换筛选/翻页时保留旧表格（keep），否则列表被骨架替掉、页面高度一塌就会跳回顶部
  const { state, reload, refreshing } = useLoad(
    () => ownerApi.adminAccounts(query), [JSON.stringify(query)], { keep: true },
  );

  if (me.state.k === 'ok' && me.state.data.tier !== 'admin') return <NoAccess what="权限管理" />;

  const hasFilter = !!(q.trim() || debounced || tier || status || expiry || from || to || hasData);

  function resetFilters() {
    setQ('');
    setDebounced('');
    setTier('');
    setStatus('');
    setExpiry('');
    setFrom('');
    setTo('');
    setHasData('');
    setPage(1);
  }

  /** 手填起始日期时若晚于结束日期，直接把两个框对调，别让人对着倒置的区间猜结果。 */
  function changeFrom(v: string) {
    setPage(1);
    if (v && to && v > to) {
      setFrom(to);
      setTo(v);
      return;
    }
    setFrom(v);
  }
  function changeTo(v: string) {
    setPage(1);
    if (v && from && v < from) {
      setTo(from);
      setFrom(v);
      return;
    }
    setTo(v);
  }
  function applyRange(f: Date, t: Date) {
    setFrom(ymd(f));
    setTo(ymd(t));
    setPage(1);
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

  /** 打开「改有效期」：预填当前到期时间；本来就是永久就先把输入框填成 6 个月后，方便直接改。 */
  function openExpiry(a: AdminAccount) {
    const now = Math.floor(Date.now() / 1000);
    setTierFor(null);
    setPwFor(null);
    setExpFor(a);
    setExpPermanent(a.tier_expires_at == null);
    setExpValue(toLocalInput(a.tier_expires_at ?? addMonthsTs(now, 6)));
    setFlash(null);
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

  async function doExpiry() {
    if (!expFor) return;
    const now = Math.floor(Date.now() / 1000);
    const ts = expPermanent ? null : (fromLocalInput(expValue) ?? null);
    if (!expPermanent && ts == null) {
      return setFlash({ kind: 'err', text: '请先选择到期时间（精确到秒）' });
    }
    if (ts != null && ts <= now) {
      return setFlash({ kind: 'err', text: '有效期必须晚于当前时间；要让账号立即失效，请用「设为免费」' });
    }
    setBusy(true);
    try {
      await ownerApi.adminSetExpiry(expFor.id, ts);
      // 不弹成功提示：弹窗一关、表格里那一行的有效期就变了，本身就是反馈
      setExpFor(null);
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
  const totalAll = s ? s.free + s.full + s.admin : 0;
  const nowSec = Math.floor(Date.now() / 1000);
  const expTs = fromLocalInput(expValue);
  const expPreview = expPermanent
    ? '永久有效，不会到期。'
    : expTs == null
      ? '还没选时间。'
      : `将设为 ${fmtDateTime(expTs)}（还剩 ${Math.max(0, Math.ceil((expTs - nowSec) / 86400))} 天）。`;
  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;

  /** 行内操作：管理员只有重置密码；其余按状态给「撤销注销 / 恢复」，风险项收进「更多」。
   *
   *  槽位是固定的 [改有效期] [重置密码] [⋯]：管理员行没有改有效期，用一枚隐藏的同字按钮占位，
   *  这样三种行（有数据 / 全 0 / 管理员）的「重置密码」落在同一条竖线上，不往左偏。
   */
  function rowOps(a: AdminAccount) {
    const openPw = () => {
      setExpFor(null);
      setTierFor(null);
      setPwFor(a);
      setPw1('');
      setPw2('');
    };
    const more = [];
    if (a.admin) {
      // 管理员没有更多操作
    } else if (a.status === 'active') {
      more.push({
        text: a.tier === 'full' ? '设为免费' : '设为完整版',
        onClick: () => { setExpFor(null); setPwFor(null); setTierFor(a); setTierMonths('6m'); },
      });
      more.push({ text: '停用', danger: true, divider: true, onClick: () => void doStatus(a, 'disabled') });
    } else if (a.status === 'disabled') {
      more.push({ text: '恢复', onClick: () => void doStatus(a, 'active') });
    } else {
      more.push({ text: '撤销注销', onClick: () => void doRestore(a) });
      more.push({ text: '停用', danger: true, divider: true, onClick: () => void doStatus(a, 'disabled') });
    }
    return (
      <div class="row-ops">
        {a.tier === 'full' ? (
          <button type="button" class="text-btn" disabled={busy} onClick={() => openExpiry(a)}>改有效期</button>
        ) : (
          // 占位：字相同、只是看不见，宽度天然一致（不写死 px，字体变了也不会错位）
          <span class="text-btn op-ph" aria-hidden="true">改有效期</span>
        )}
        <button type="button" class="text-btn" disabled={busy} onClick={openPw}>重置密码</button>
        {a.admin ? (
          <span class="op-ph-icon" aria-hidden="true" />
        ) : (
          <Menu variant="icon" ariaLabel="更多操作" align="right" disabled={busy} items={more} />
        )}
      </div>
    );
  }

  return (
    <>
      <div class="page-head">
        <h1>权限管理</h1>
      </div>

      {s ? (
        <div class="stat-grid">
          <div class="stat-card"><div class="stat-k">账号总数</div><div class="stat-v">{totalAll}</div></div>
          <div class="stat-card"><div class="stat-k">免费版</div><div class="stat-v">{s.free}</div></div>
          <div class="stat-card"><div class="stat-k">完整版</div><div class="stat-v">{s.full}</div></div>
          <div class="stat-card"><div class="stat-k">30 天内到期</div><div class="stat-v">{s.expiring_30d}</div></div>
          <div class="stat-card">
            <div class="stat-k">已停用 / 待注销</div>
            <div class="stat-v">{s.disabled}<span class="stat-sep">/</span>{s.pending_deletion}</div>
          </div>
        </div>
      ) : null}

      <div class="filter-box">
        <div class="filter-line">
          <input class="search" type="search" placeholder="搜索登录名" aria-label="搜索登录名"
                 value={q} onInput={(e) => setQ(e.currentTarget.value)} />
          <Segmented
            ariaLabel="按等级筛选"
            value={tier}
            onChange={(k) => { setTier(k as '' | Tier); setPage(1); }}
            items={[
              { key: '', label: '全部', n: totalAll },
              { key: 'free', label: '免费版', n: s?.free },
              { key: 'full', label: '完整版', n: s?.full },
              { key: 'admin', label: '管理员', n: s?.admin },
            ]}
          />
        </div>
        <div class="filter-line">
          <span class="ctrl-fixed">
            <Select ariaLabel="按状态筛选" value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={[
              { value: '', label: '全部状态' }, { value: 'active', label: '正常' },
              { value: 'disabled', label: '已停用' }, { value: 'pending_deletion', label: '待注销' },
            ]} />
          </span>
          <span class="ctrl-fixed">
            <Select ariaLabel="按到期筛选" value={expiry} onChange={(v) => { setExpiry(v); setPage(1); }} options={[
              { value: '', label: '全部有效期' }, { value: 'permanent', label: '永久' },
              { value: 'soon', label: '30 天内到期' }, { value: 'expired', label: '已过期' },
            ]} />
          </span>
          <span class="ctrl-fixed">
            <Select ariaLabel="按数据量筛选" value={hasData} onChange={(v) => { setHasData(v); setPage(1); }} options={[
              { value: '', label: '有无数据都看' }, { value: '1', label: '有曲目' }, { value: '0', label: '无曲目' },
            ]} />
          </span>
          <span class="date-range">
            <span class="date-label">创建时间</span>
            <DateInput label="创建时间开始" placeholder="开始日期" value={from} onInput={changeFrom} />
            <span class="muted">至</span>
            <DateInput label="创建时间截止" placeholder="结束日期" value={to} onInput={changeTo} />
            <Menu
              label="快捷 ▾"
              variant="boxed"
              ariaLabel="创建时间快捷范围"
              items={[
                { text: '今天', onClick: () => applyRange(new Date(), new Date()) },
                { text: '近 7 天', onClick: () => applyRange(daysAgo(6), new Date()) },
                { text: '近 30 天', onClick: () => applyRange(daysAgo(29), new Date()) },
                {
                  text: '本月',
                  onClick: () => applyRange(new Date(new Date().getFullYear(), new Date().getMonth(), 1), new Date()),
                },
                { text: '清除日期', onClick: () => { setFrom(''); setTo(''); setPage(1); } },
              ]}
            />
          </span>
          {hasFilter ? (
            <button type="button" class="text-btn filter-reset" onClick={resetFilters}>重置筛选</button>
          ) : null}
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

      {expFor ? (
        <Modal title={`设置「${expFor.login_name}」的有效期`} onClose={() => setExpFor(null)}>
          <div class="filter-row">
            <label><input type="radio" name="expmode" checked={!expPermanent} onChange={() => setExpPermanent(false)} /> 指定到期时间</label>
            <label><input type="radio" name="expmode" checked={expPermanent} onChange={() => setExpPermanent(true)} /> 永久</label>
          </div>
          {expPermanent ? null : (
            <>
              <div class="filter-row">
                <input
                  type="datetime-local"
                  step="1"
                  aria-label="新的到期时间（精确到秒）"
                  value={expValue}
                  disabled={busy}
                  onInput={(e) => setExpValue(e.currentTarget.value)}
                />
                <button type="button" class="btn btn-ghost small" disabled={busy}
                        onClick={() => setExpValue(toLocalInput(addMonthsTs(expTs ?? nowSec, 6)))}>
                  +6 个月
                </button>
                <button type="button" class="btn btn-ghost small" disabled={busy}
                        onClick={() => setExpValue(toLocalInput(addMonthsTs(expTs ?? nowSec, 12)))}>
                  +1 年
                </button>
              </div>
              <p class="small muted">{expPreview}</p>
            </>
          )}
          <div class="filter-row modal-actions">
            <button type="button" class="btn" disabled={busy} onClick={() => void doExpiry()}>保存</button>
            <button type="button" class="btn btn-ghost" disabled={busy} onClick={() => setExpFor(null)}>取消</button>
          </div>
        </Modal>
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

      <section class="box">
        {state.k === 'loading' ? <Loading what="账号列表" /> : null}
        {state.k === 'error' ? <ErrorBox message={state.message} retry={reload} /> : null}

        {data && data.items.length === 0 ? (
          <div class="empty-state">
            <p>没有符合条件的账号。</p>
            {hasFilter ? (
              <button type="button" class="btn small" onClick={resetFilters}>重置筛选</button>
            ) : null}
          </div>
        ) : null}

        {data && data.items.length ? (
          <div class={refreshing ? 'table-wrap is-refreshing' : 'table-wrap'}>
            <table class="table accounts-table">
              {/* 列宽 = colgroup 里每列的 CSS 变量；变量在 styles.css 按屏宽切换
                  （1280 屏数字列 48px、≥1440px 80px）。直接用 `<col style={{width:'48px'}}>`
                  就没法被 media query 覆盖了。 */}
              <colgroup>
                {COLS.map((c) => <col style={{ width: `var(--${c.cls})` }} />)}
              </colgroup>
              <thead>
                <tr>
                  {COLS.map((c) => (
                    <th class={[c.cls, c.mid ? 'mid' : '', c.cls === 'c-ops' ? 'cell-ops' : ''].filter(Boolean).join(' ')}>
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
                    <td class="cell-name">
                      <div class="name" title={a.login_name}>{a.login_name}</div>
                      <div class="sub">{a.active_sessions} 台在线</div>
                    </td>
                    <td><span class={`badge badge-${a.tier}`}>{TIER_LABEL[a.tier]}</span></td>
                    <td>{expiryCell(a)}</td>
                    <td>{statusCell(a)}</td>
                    <td>{dtCell(a.created_at)}</td>
                    <td class="cell-active" title={a.last_seen_at ? fmtDateTime(a.last_seen_at) : undefined}>
                      {activeText(a.last_seen_at)}
                    </td>
                    <td class={a.audio ? 'mid' : 'mid zero'}>{a.audio}</td>
                    <td class={a.video ? 'mid' : 'mid zero'}>{a.video}</td>
                    <td class={a.playlists ? 'mid' : 'mid zero'}>{a.playlists}</td>
                    <td class={a.bytes ? 'mid' : 'mid zero'}>{byteCell(a.bytes)}</td>
                    <td class="cell-ops">{rowOps(a)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}

        {/* 「共 N 条」常显：只有一页时也告诉人一共多少；空列表交给上面的空状态说话 */}
        {data && data.items.length ? (
          <div class="box-foot">
            <span class="small muted">共 {data.total} 条</span>
            {data.total > data.size ? (
              <span class="foot-page">
                <button type="button" class="btn small" disabled={page <= 1} onClick={() => setPage((p) => Math.max(1, p - 1))}>上一页</button>
                <span class="small muted">第 {data.page} / {pages} 页</span>
                <button type="button" class="btn small" disabled={page >= pages} onClick={() => setPage((p) => p + 1)}>下一页</button>
              </span>
            ) : null}
          </div>
        ) : null}
      </section>
    </>
  );
}
