/** 邀请码（仅管理员）：批量生成升级券，明文只显示一次。
 *
 * 服务端只存 HMAC，列表接口拿不到明文（只有 id / 时间 / 使用人），所以明文**只能**在
 * 生成响应里出现一次：生成成功后停在工具栏下面一块「本次生成」结果区，关掉就真的没了。
 *
 * 页面自上而下：标题 → 工具栏（状态分段 + 生成区）→ 本次生成结果（有才显示）→
 * 表格卡片（固定列宽、单行）→ 卡片底部「共 N 条」+ 分页。
 * 视觉规范（卡片、间距、分段控件、徽章、时间格式）与「权限管理」页同一套，见 styles.css 的「邀请码页」段。
 *
 * 用途只有一种：把免费账号升成完整版 6 个自然月。没有"注册准入"这种选项。
 */
import { useEffect, useRef, useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';
import { ownerApi, type InviteItem, type InviteState } from '../api';
import { DateTimeCell, ErrorBox, Loading, NoAccess, Segmented, useLoad, useToast } from '../ui';

const MIN = 1;
const MAX = 50;

const STATE_LABEL: Record<InviteState, string> = { unused: '未使用', used: '已使用', expired: '已过期' };

/** 列。宽度由 styles.css 的 `--inv-*` 变量给，`<col style="width: var(--inv-*)">` 引用
 *  （和权限管理页同一套做法：`table-layout: fixed` 下只有这一种写法在 Chrome 里稳）。 */
const COLS: { label: string; cls: string }[] = [
  { label: '状态', cls: 'inv-c-state' },
  { label: '创建时间', cls: 'inv-c-created' },
  { label: '到期时间', cls: 'inv-c-expires' },
  { label: '使用人', cls: 'inv-c-user' },
  { label: '使用时间', cls: 'inv-c-used' },
];

/** 生成后的行短暂高亮用的类名（2 秒后淡出，见 styles.css 的 `.inv-fresh`） */
const FRESH_MS = 2000;

type Counts = { all: number; unused: number; used: number; expired: number };

/** 各状态的数量：列表接口只给"当前筛选下的 total"，所以三种状态各问一次，只要一条（size=1）。
 *  「全部」= 三者相加（服务端的状态划分互斥且穷尽，加起来就是全量，省一条请求）。 */
async function fetchCounts(): Promise<Counts> {
  const [u, s, e] = await Promise.all([
    ownerApi.adminInvites('unused', 1, 1),
    ownerApi.adminInvites('used', 1, 1),
    ownerApi.adminInvites('expired', 1, 1),
  ]);
  return { unused: u.total, used: s.total, expired: e.total, all: u.total + s.total + e.total };
}

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

/** 复制图标：两个叠起来的方框。做成内联 SVG，省一个请求也不用引图标库。 */
function CopyIcon() {
  return (
    <svg viewBox="0 0 16 16" width="13" height="13" aria-hidden="true" fill="none"
         stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
      <rect x="5.5" y="5.5" width="8" height="8" rx="1.5" />
      <path d="M10.5 3.5v-1a1 1 0 0 0-1-1h-6a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h1" />
    </svg>
  );
}

export function InvitesPage() {
  const me = useLoad(() => ownerApi.me());
  const { show: toast, node: toastNode } = useToast();
  const [count, setCount] = useState('1');
  const [busy, setBusy] = useState(false);
  const [codes, setCodes] = useState<string[] | null>(null);
  const [copied, setCopied] = useState<string | null>(null);
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(1);
  const [counts, setCounts] = useState<Counts | null>(null);
  const [fresh, setFresh] = useState<number[]>([]);
  // 换筛选/翻页保留旧列表（keep）：不抽掉表格换骨架，页面高度不变、不会跳回顶部
  const list = useLoad(() => ownerApi.adminInvites(status, page), [status, page], { keep: true });

  const n = Number(count);
  const bad = !Number.isInteger(n) || n < MIN || n > MAX;

  /** 生成完要认出来的那几行：列表按 created_at 倒序，新码就在第一页最上面 */
  const pendingFresh = useRef(0);
  const freshTimer = useRef<number | undefined>(undefined);

  const isAdmin = me.state.k === 'ok' && me.state.data.tier === 'admin';

  /** 数量只是锦上添花：拿不到就不显示，不该让整页报错，也不该给非管理员白发请求。 */
  function reloadCounts() {
    void fetchCounts().then(setCounts, () => {});
  }
  useEffect(() => {
    if (isAdmin) reloadCounts();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAdmin]);

  // 生成 → 刷新列表 → 把最上面那 N 行点亮 2 秒
  useEffect(() => {
    if (!pendingFresh.current) return;
    if (list.state.k === 'error') { pendingFresh.current = 0; return; }
    if (list.state.k !== 'ok') return;
    const want = pendingFresh.current;
    pendingFresh.current = 0;
    // 只有第一页、且筛选看得见未使用的码时才有"新行"可言
    if (page !== 1 || (status !== '' && status !== 'unused')) return;
    const items = list.state.data.items;
    if (!items.length) return;
    setFresh(items.slice(0, want).map((i) => i.id));
    if (freshTimer.current !== undefined) window.clearTimeout(freshTimer.current);
    freshTimer.current = window.setTimeout(() => setFresh([]), FRESH_MS);
  }, [list.state, page, status]);

  useEffect(() => () => {
    if (freshTimer.current !== undefined) window.clearTimeout(freshTimer.current);
  }, []);

  if (me.state.k === 'ok' && me.state.data.tier !== 'admin') return <NoAccess what="邀请码" />;

  function step(delta: number) {
    const base = Number.isFinite(n) ? n : MIN;
    setCount(String(Math.min(MAX, Math.max(MIN, base + delta))));
  }

  async function generate() {
    if (bad) return;
    setBusy(true);
    try {
      const r = await ownerApi.adminCreateInvites(n);
      setCodes(r.codes);
      setCopied(null);
      pendingFresh.current = r.codes.length;
      list.reload();
      reloadCounts();
      toast(`已生成 ${r.codes.length} 个邀请码`);
    } catch (e) {
      // 失败不动输入框里的数量，方便直接重试
      toast(messageFor(e), 'err');
    } finally {
      setBusy(false);
    }
  }

  async function copyOne(code: string) {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(code);
      window.setTimeout(() => setCopied((v) => (v === code ? null : v)), 1400);
      toast('已复制邀请码');
    } catch {
      toast('浏览器不允许写入剪贴板，请用「下载 .txt」', 'err');
    }
  }

  async function copyAll() {
    if (!codes) return;
    try {
      await navigator.clipboard.writeText(codes.join('\n'));
      toast(`已复制 ${codes.length} 个邀请码`);
    } catch {
      toast('浏览器不允许写入剪贴板，请用「下载 .txt」', 'err');
    }
  }

  function save() {
    if (!codes) return;
    download(codes);
    toast(`已下载 ${codes.length} 个邀请码`);
  }

  function closeCodes() {
    setCodes(null);
    setCopied(null);
  }

  const data = list.state.k === 'ok' ? list.state.data : null;
  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;
  const hasFilter = status !== '';

  return (
    <>
      {toastNode}

      <div class="page-head">
        <h1>邀请码</h1>
      </div>

      {/* 工具栏：左边筛状态（带数量），右边生成。窄屏各自占一整行 */}
      <div class="inv-bar">
        <Segmented
          ariaLabel="按状态筛选"
          value={status}
          onChange={(k) => { setStatus(k); setPage(1); }}
          items={[
            { key: '', label: '全部', n: counts?.all },
            { key: 'unused', label: '未使用', n: counts?.unused },
            { key: 'used', label: '已使用', n: counts?.used },
            { key: 'expired', label: '已过期', n: counts?.expired },
          ]}
        />

        <div class="inv-gen">
          <label class="inv-gen-k" for="inv-count">生成数量</label>
          <div class={bad ? 'stepper is-bad' : 'stepper'}>
            <button type="button" class="stepper-btn" aria-label="减少一个"
                    disabled={busy || n <= MIN} onClick={() => step(-1)}>−</button>
            <input
              id="inv-count"
              class="stepper-input"
              type="number"
              inputmode="numeric"
              min={MIN}
              max={MAX}
              step="1"
              placeholder={`1–${MAX}`}
              title={`${MIN}–${MAX}，默认 1`}
              aria-invalid={bad}
              aria-describedby={bad ? 'inv-count-err' : undefined}
              value={count}
              onInput={(e) => setCount(e.currentTarget.value)}
            />
            <button type="button" class="stepper-btn" aria-label="增加一个"
                    disabled={busy || n >= MAX} onClick={() => step(1)}>+</button>
          </div>
          <button type="button" class="btn inv-gen-btn" disabled={busy || bad} onClick={() => void generate()}>
            {busy ? '生成中…' : '＋ 生成邀请码'}
          </button>
        </div>
      </div>

      {/* 越界提示贴在工具栏下方、靠右对齐，正好落在输入框下面；不再挤在输入框和按钮中间 */}
      {bad ? <p class="inv-err" id="inv-count-err">数量要是 {MIN}–{MAX} 之间的整数。</p> : null}

      {/* 本次生成：明文只在这里出现一次，所以给足存在感（复制 / 下载 / 关闭） */}
      {codes ? (
        <div class="inv-codes">
          <div class="inv-codes-head">
            <h2>已生成 {codes.length} 个</h2>
            <span class="inv-note">只显示这一次，关闭后无法再查看</span>
            <div class="inv-codes-acts">
              {/* 全页只留「生成邀请码」一个主按钮，这里三个都是次要动作 */}
              <button type="button" class="btn btn-ghost small" onClick={() => void copyAll()}>复制全部</button>
              <button type="button" class="btn btn-ghost small" onClick={save}>下载 .txt</button>
              <button type="button" class="btn btn-ghost small" onClick={closeCodes}>关闭</button>
            </div>
          </div>
          <ul class="inv-list">
            {codes.map((c) => (
              <li class="inv-row">
                <code class="inv-code">{c}</code>
                <button
                  type="button"
                  class={copied === c ? 'inv-copy on' : 'inv-copy'}
                  title="复制邀请码"
                  aria-label={`复制邀请码 ${c}`}
                  onClick={() => void copyOne(c)}
                >
                  {copied === c ? '✓' : <CopyIcon />}
                </button>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      <div class="box">
        {list.state.k === 'loading' ? <Loading what="邀请码" /> : null}
        {list.state.k === 'error' ? <ErrorBox message={list.state.message} retry={list.reload} /> : null}

        {data && data.items.length === 0 ? (
          <div class="empty-state">
            <p>{hasFilter ? '没有符合条件的邀请码。' : '还没有生成过邀请码。'}</p>
            {hasFilter ? (
              <button type="button" class="btn small" onClick={() => { setStatus(''); setPage(1); }}>看全部</button>
            ) : null}
          </div>
        ) : null}

        {data && data.items.length ? (
          <div class={list.refreshing ? 'table-wrap is-refreshing' : 'table-wrap'}>
            <table class="table inv-table">
              <colgroup>
                {COLS.map((c) => <col style={{ width: `var(--${c.cls})` }} />)}
              </colgroup>
              <thead>
                <tr>{COLS.map((c) => <th class={c.cls}>{c.label}</th>)}</tr>
              </thead>
              <tbody>
                {data.items.map((i: InviteItem) => (
                  <tr class={fresh.includes(i.id) ? 'inv-fresh' : ''}>
                    <td><span class={`badge badge-${i.state}`}>{STATE_LABEL[i.state]}</span></td>
                    <td><DateTimeCell at={i.created_at} /></td>
                    <td><DateTimeCell at={i.expires_at} cls={i.state === 'expired' ? 'text-bad' : ''} /></td>
                    <td class="inv-user" title={i.used_by ?? undefined}>{i.used_by ?? '—'}</td>
                    <td><DateTimeCell at={i.used_at} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : null}

        {data && data.items.length ? (
          <div class="box-foot">
            <span class="small muted">共 {data.total} 条</span>
            {pages > 1 ? (
              <span class="foot-page">
                <button type="button" class="btn small" disabled={page <= 1}
                        onClick={() => setPage((p) => Math.max(1, p - 1))}>上一页</button>
                <span class="small muted">第 {data.page} / {pages} 页</span>
                <button type="button" class="btn small" disabled={page >= pages}
                        onClick={() => setPage((p) => p + 1)}>下一页</button>
              </span>
            ) : null}
          </div>
        ) : null}
      </div>
    </>
  );
}
