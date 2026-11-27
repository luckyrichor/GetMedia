/** 邀请码（仅管理员）：批量生成升级券，明文只显示一次。
 *
 * 服务端只存 HMAC，列表接口拿不到明文（只有 id / 时间 / 使用人），所以明文**只能**在
 * 生成响应里出现一次：生成成功后停在工具栏下面一块「本次生成」结果区，关掉就真的没了。
 *
 * **只看还能用的码**：已使用 / 已过期的行服务端每小时扫一次、超过 1 天就物理删除
 * （`service.py:sweep_invites`），在列表里最多活 24~25 小时，当不了台账；而"谁升级了"
 * 在「权限管理」的等级 + 有效期里本来就看得到。所以这里不再给状态筛选，只查 `status=unused`。
 *
 * 一行 = **一批**（同一次生成的所有码 `created_at` 完全相同）：单独一个码除了时间没有任何
 * 可辨认信息，按批看才知道"哪批还剩几个、什么时候废"。
 *
 * 页面自上而下：标题 → 工具栏（当前可用 + 生成区）→ 本次生成结果（有才显示）→
 * 批次表格 → 卡片底部「共 N 个」。视觉规范与「权限管理」页同一套，见 styles.css 的「邀请码页」段。
 *
 * 用途只有一种：把免费账号升成完整版 6 个自然月。没有"注册准入"这种选项。
 */
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';
import { ownerApi, type InviteItem } from '../api';
import { DateTimeCell, ErrorBox, Loading, NoAccess, useLoad, useToast } from '../ui';

const MIN = 1;
const MAX = 50;
/** 一次拉这么多码：未使用的码 1 天内就过期，实际不会超过这个数（服务端上限也是 200） */
const PAGE_SIZE = 200;

/** 列。宽度由 styles.css 的 `--inv-*` 变量给，`<col style="width: var(--inv-*)">` 引用
 *  （和权限管理页同一套做法：`table-layout: fixed` 下只有这一种写法在 Chrome 里稳）。 */
const COLS: { label: string; cls: string }[] = [
  { label: '生成时间', cls: 'inv-c-created' },
  { label: '可用数量', cls: 'inv-c-count' },
  { label: '到期时间', cls: 'inv-c-expires' },
  { label: '剩余时间', cls: 'inv-c-left' },
];

/** 生成后的那一批短暂高亮（2 秒后淡出，见 styles.css 的 `.inv-fresh`） */
const FRESH_MS = 2000;

/** 剩余时间：只剩几十分钟时给「N 分」，别显示成「0 小时 3 分」 */
function leftText(sec: number): string {
  if (sec <= 0) return '已过期';
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  if (h > 0) return `${h} 小时 ${m} 分`;
  return `${Math.max(1, m)} 分`;
}

type Batch = {
  /** `${created_at}|${expires_at}`：同一次生成的码这两个值完全一样 */
  key: string;
  createdAt: number;
  expiresAt: number;
  /** 这一批还剩几个没用 */
  n: number;
  /** 这一批码的总寿命（秒），用来算进度条；正常是 86400 */
  life: number;
};

function toBatches(items: InviteItem[]): Batch[] {
  const map = new Map<string, Batch>();
  for (const it of items) {
    const key = `${it.created_at}|${it.expires_at ?? 0}`;
    const hit = map.get(key);
    if (hit) hit.n += 1;
    else {
      map.set(key, {
        key,
        createdAt: it.created_at,
        expiresAt: it.expires_at ?? 0,
        n: 1,
        life: Math.max(1, (it.expires_at ?? it.created_at) - it.created_at),
      });
    }
  }
  return [...map.values()].sort((a, b) => b.createdAt - a.createdAt);
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
  /** 刚生成的那一批的 key，用来高亮 2 秒 */
  const [freshKey, setFreshKey] = useState<string | null>(null);
  // 换筛选/翻页保留旧列表（keep）：不抽掉表格换骨架，页面高度不变、不会跳回顶部
  const list = useLoad(() => ownerApi.adminInvites('unused', 1, PAGE_SIZE), [], { keep: true });

  const n = Number(count);
  const bad = !Number.isInteger(n) || n < MIN || n > MAX;

  /** 生成完要认出来的那一批：待高亮的码数 */
  const pendingFresh = useRef(0);
  const freshTimer = useRef<number | undefined>(undefined);

  const items = list.state.k === 'ok' ? list.state.data.items : [];
  const batches = useMemo(() => toBatches(items), [items]);

  // 生成 → 刷新列表 → 把新那一批点亮 2 秒
  useEffect(() => {
    if (!pendingFresh.current) return;
    if (list.state.k === 'error') { pendingFresh.current = 0; return; }
    if (list.state.k !== 'ok') return;
    const want = pendingFresh.current;
    pendingFresh.current = 0;
    // 同一次生成的所有码 created_at 一样，所以"数量对得上"的那一批就是刚生成的
    const hit = toBatches(list.state.data.items).find((b) => b.n === want);
    if (!hit) return;
    setFreshKey(hit.key);
    if (freshTimer.current !== undefined) window.clearTimeout(freshTimer.current);
    freshTimer.current = window.setTimeout(() => setFreshKey(null), FRESH_MS);
  }, [list.state]);

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
  const total = data?.total ?? 0;
  const now = Math.floor(Date.now() / 1000);

  return (
    <>
      {toastNode}

      <div class="page-head">
        <h1>邀请码</h1>
      </div>

      {/* 工具栏：左边是当前可用的总数，右边是生成。窄屏各自占一整行 */}
      <div class="inv-bar">
        <div class="inv-sum">
          <span class="inv-sum-k">当前可用</span>
          <span class="inv-sum-v">{total}</span>
          <span class="inv-sum-u">个</span>
        </div>

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

        {data && batches.length === 0 ? (
          <div class="empty-state">
            <p>{total === 0 ? '还没有生成过邀请码。' : '当前没有可用的邀请码。'}</p>
          </div>
        ) : null}

        {data && batches.length ? (
          <div class={list.refreshing ? 'table-wrap is-refreshing' : 'table-wrap'}>
            <table class="table inv-table">
              <colgroup>
                {COLS.map((c) => <col style={{ width: `var(--${c.cls})` }} />)}
              </colgroup>
              <thead>
                <tr>{COLS.map((c) => <th class={c.cls}>{c.label}</th>)}</tr>
              </thead>
              <tbody>
                {batches.map((b) => {
                  const left = b.expiresAt - now;
                  const pct = Math.max(0, Math.min(100, (left / b.life) * 100));
                  // 2 小时内到期标红、6 小时内标黄，其余正常色（表格和进度条同一套色）
                  const soon = left <= 2 * 3600 ? 'bad' : left <= 6 * 3600 ? 'warn' : '';
                  return (
                    <tr class={b.key === freshKey ? 'inv-fresh' : ''}>
                      <td><DateTimeCell at={b.createdAt} /></td>
                      <td class="inv-count">{b.n} 个</td>
                      <td><DateTimeCell at={b.expiresAt} cls={soon ? `text-${soon}` : ''} /></td>
                      <td>
                        <span class={soon ? `inv-left is-${soon}` : 'inv-left'}>
                          <span class="inv-left-track"><i style={{ width: `${pct}%` }} /></span>
                          <span class="inv-left-t">还剩 {leftText(left)}</span>
                        </span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : null}

        {data && batches.length ? (
          <div class="box-foot">
            <span class="small muted">
              共 {total} 个可用
              {items.length < total ? `（只显示最近 ${items.length} 个）` : ''}
            </span>
            <span class="small muted inv-foot-note">已使用 / 已过期的码保留 1 天后自动清理，不再列出</span>
          </div>
        ) : null}
      </div>
    </>
  );
}
