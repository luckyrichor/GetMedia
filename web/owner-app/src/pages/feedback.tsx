/** 5c：反馈收件箱。
 *
 * 这条通道**跨账号**读所有用户的反馈（唯一一条），所以：
 * - 列表只显示账号编号前 8 位，不显示登录名；联系方式只在详情里、并标注"用户自填"；
 * - 图片按"条目 + 序号"取，页面不拼文件名；
 * - 改状态会写服务端审计，页面只做"改完把列表拉回第一页"这一件事。
 */
import { useEffect, useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';
import {
  feedbackImageUrl,
  ownerApi,
  type FeedbackCounts,
  type FeedbackDetail,
  type FeedbackItem,
  type FeedbackStatus,
} from '../api';
import { Chips, ErrorBox, Field, fmtBytes, fmtTime, Loading, Empty, relTime } from '../ui';
import { Select } from '../select';

type Filter = FeedbackStatus | 'all';

const FILTERS: { key: Filter; label: string }[] = [
  { key: 'all', label: '全部' },
  { key: 'new', label: '待处理' },
  { key: 'handled', label: '已处理' },
  { key: 'ignored', label: '已忽略' },
];

const STATUS_LABEL: Record<FeedbackStatus, string> = { new: '待处理', handled: '已处理', ignored: '已忽略' };
const CATEGORY_LABEL: Record<string, string> = { bug: '故障', suggestion: '建议', account: '账号', other: '其他' };
const MAIL_LABEL: Record<string, string> = { pending: '邮件待发', sent: '邮件已发', failed: '邮件失败', skipped: '未发邮件' };
const PAGE = 20;

/** 日期筛选的三个值：空串表示不限。 */
type DateRange = { from: string; to: string };

/** 把 `YYYY-MM-DD` 转成本地时区的 Unix 秒（起/止含当天）。 */
function dayToUnix(d: string, end: boolean): number | null {
  if (!d) return null;
  const dt = new Date(`${d}T00:00:00`);
  if (Number.isNaN(dt.getTime())) return null;
  return Math.floor(dt.getTime() / 1000) + (end ? 86399 : 0);
}

export function FeedbackPage() {
  const [filter, setFilter] = useState<Filter>('all');
  const [category, setCategory] = useState('');
  const [mailState, setMailState] = useState('');
  const [hasImage, setHasImage] = useState('');
  const [hasContact, setHasContact] = useState('');
  const [dates, setDates] = useState<DateRange>({ from: '', to: '' });
  const [userPrefix, setUserPrefix] = useState('');
  const [items, setItems] = useState<FeedbackItem[]>([]);
  const [counts, setCounts] = useState<FeedbackCounts | null>(null);
  const [total, setTotal] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [phase, setPhase] = useState<'loading' | 'ok' | 'error'>('loading');
  const [err, setErr] = useState('');
  const [openId, setOpenId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function fetchPage(offset: number, replace: boolean) {
    const out = await ownerApi.feedbackPage({
      status: filter,
      category: category || undefined,
      mail_state: mailState || undefined,
      has_image: hasImage === '' ? undefined : hasImage === 'yes',
      has_contact: hasContact === '' ? undefined : hasContact === 'yes',
      date_from: dayToUnix(dates.from, false) ?? undefined,
      date_to: dayToUnix(dates.to, true) ?? undefined,
      user_prefix: userPrefix.trim() || undefined,
      limit: PAGE,
      offset,
    });
    setItems((prev) => (replace ? out.items : [...prev, ...out.items]));
    setCounts(out.counts);
    setTotal(out.total);
    setHasMore(out.has_more);
  }

  async function reload() {
    setPhase('loading');
    try {
      await fetchPage(0, true);
      setPhase('ok');
    } catch (e) {
      setErr(messageFor(e));
      setPhase('error');
    }
  }

  useEffect(() => {
    setOpenId(null);
    void reload();
    // 只在筛选变化时重取；reload 内部不依赖其他外部状态
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filter, category, mailState, hasImage, hasContact, dates.from, dates.to, userPrefix]);

  async function more() {
    if (busy) return;
    setBusy(true);
    try {
      await fetchPage(items.length, false);
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function setStatus(id: string, status: FeedbackStatus) {
    if (busy) return;
    setBusy(true);
    try {
      await ownerApi.feedbackStatus(id, status);
      // 列表与计数都从服务端重取，避免本地猜"总数变了没有"
      await fetchPage(0, true);
      setPhase('ok');
      setOpenId(null);
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  const chips = FILTERS.map((f) => ({
    key: f.key,
    label: f.label,
    n: counts ? (f.key === 'all' ? counts.all : counts[f.key]) : undefined,
  }));

  return (
    <>
      <h1>反馈收件箱</h1>

      <Chips items={chips} value={filter} onChange={(k) => setFilter(k as Filter)} ariaLabel="按状态筛选" />

      {/* 筛选栏：每项空 = 不筛，多项之间是「且」 */}
      <div class="filter-bar">
        <div class="filter">
          <span>类别</span>
          <Select
            ariaLabel="筛选：类别"
            value={category}
            options={[
              { value: '', label: '全部类别' },
              ...Object.entries(CATEGORY_LABEL).map(([v, l]) => ({ value: v, label: l })),
            ]}
            onChange={setCategory}
          />
        </div>
        <div class="filter">
          <span>邮件状态</span>
          <Select
            ariaLabel="筛选：邮件状态"
            value={mailState}
            options={[
              { value: '', label: '全部' },
              ...Object.entries(MAIL_LABEL).map(([v, l]) => ({ value: v, label: l })),
            ]}
            onChange={setMailState}
          />
        </div>
        <div class="filter">
          <span>图片</span>
          <Select
            ariaLabel="筛选：是否带图片"
            value={hasImage}
            options={[
              { value: '', label: '不限' },
              { value: 'yes', label: '有图片' },
              { value: 'no', label: '无图片' },
            ]}
            onChange={setHasImage}
          />
        </div>
        <div class="filter">
          <span>联系方式</span>
          <Select
            ariaLabel="筛选：是否留联系方式"
            value={hasContact}
            options={[
              { value: '', label: '不限' },
              { value: 'yes', label: '已留' },
              { value: 'no', label: '未留' },
            ]}
            onChange={setHasContact}
          />
        </div>
        <label class="filter">
          <span>账号编号</span>
          <input
            type="text"
            placeholder="前几位，可留空"
            value={userPrefix}
            onInput={(e) => setUserPrefix((e.target as HTMLInputElement).value)}
          />
          {userPrefix && (
            <button type="button" class="filter-clear" aria-label="清除账号编号" onClick={() => setUserPrefix('')}>×</button>
          )}
        </label>
        <div class="filter filter-wide">
          <span>提交时间</span>
          <div class="filter-range">
            <input
              type="date"
              aria-label="提交时间开始"
              value={dates.from}
              onInput={(e) => setDates((d) => ({ ...d, from: (e.target as HTMLInputElement).value }))}
            />
            <span class="muted">至</span>
            <input
              type="date"
              aria-label="提交时间截止"
              value={dates.to}
              onInput={(e) => setDates((d) => ({ ...d, to: (e.target as HTMLInputElement).value }))}
            />
            {(dates.from || dates.to) && (
              <button
                type="button"
                class="filter-clear"
                aria-label="清除提交时间"
                onClick={() => setDates({ from: '', to: '' })}
              >×</button>
            )}
          </div>
        </div>
      </div>

      {phase === 'loading' && <Loading what="反馈列表" />}
      {phase === 'error' && <ErrorBox message={err} retry={() => void reload()} />}

      {phase === 'ok' && items.length === 0 && (
        <Empty>
          <strong>这个筛选下没有反馈</strong>
          <p class="small muted">
            {filter === 'all' ? '还没有人提交过反馈。' : '换一个状态看看，或点"全部"。'}
          </p>
        </Empty>
      )}

      {phase === 'ok' && items.length > 0 && (
        <ul class="feed">
          {items.map((it) => (
            <li class={openId === it.id ? 'feed-row open' : 'feed-row'}>
              <button
                type="button"
                class="feed-head"
                aria-expanded={openId === it.id}
                onClick={() => setOpenId(openId === it.id ? null : it.id)}
              >
                <span class={`dot ${it.status}`} aria-hidden="true" />
                <span class="feed-main">
                  <span class="feed-text">{it.text || '（只有图片）'}</span>
                  <span class="feed-meta small muted">
                    {relTime(it.created_at)} · {fmtTime(it.created_at)} · {CATEGORY_LABEL[it.category] ?? it.category}
                    {it.image_count > 0 ? ` · 图片 ${it.image_count} 张` : ''}
                    {it.contact ? ' · 已留' : ''}
                    {it.diag.app_version ? ` · v${it.diag.app_version}` : ''}
                    {it.diag.channel ? ` · ${it.diag.channel}` : ''}
                  </span>
                </span>
                <span class={`tag st-${it.status}`}>{STATUS_LABEL[it.status]}</span>
                {it.mail_state !== 'sent' && (
                  <span class="tag warn-tag">{MAIL_LABEL[it.mail_state] ?? it.mail_state}</span>
                )}
              </button>
              {openId === it.id && (
                <FeedbackDetailView
                  id={it.id}
                  busy={busy}
                  mailState={it.mail_state}
                  onStatus={(s) => void setStatus(it.id, s)}
                />
              )}
            </li>
          ))}
        </ul>
      )}

      {phase === 'ok' && hasMore && (
        <p>
          <button type="button" class="btn btn-ghost small" disabled={busy} onClick={() => void more()}>
            {busy ? '加载中…' : `加载更多（还有 ${Math.max(0, total - items.length)} 条）`}
          </button>
        </p>
      )}
      {phase === 'ok' && !hasMore && items.length > 0 && (
        <p class="small muted">已显示全部 {total} 条。</p>
      )}
      {err && phase === 'ok' && <div class="alert warn small">{err}</div>}
    </>
  );
}

function FeedbackDetailView({ id, busy, mailState, onStatus }: {
  id: string;
  busy: boolean;
  mailState: string;
  onStatus: (s: FeedbackStatus) => void;
}) {
  const [detail, setDetail] = useState<FeedbackDetail | null>(null);
  const [err, setErr] = useState('');
  const [note, setNote] = useState('');
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    let alive = true;
    setDetail(null);
    setErr('');
    ownerApi.feedbackDetail(id).then(
      (d) => alive && setDetail(d),
      (e) => alive && setErr(messageFor(e)),
    );
    return () => {
      alive = false;
    };
  }, [id]);

  if (err) return <div class="feed-body"><div class="alert warn small">{err}</div></div>;
  if (!detail) return <div class="feed-body"><Loading what="反馈详情" /></div>;

  const diagLines = Object.entries(detail.diag).map(([k, v]) => `${k}: ${v}`).join('\n');

  async function copy() {
    const text = [
      `编号：${detail!.id}`,
      `时间：${fmtTime(detail!.created_at)}`,
      `账号：${detail!.user_ref}…`,
      `类别：${detail!.category}`,
      `联系方式：${detail!.contact || '-'}`,
      '',
      detail!.text || '(无文字)',
      '',
      '— 诊断 —',
      diagLines || '(无)',
    ].join('\n');
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setNote('');
    } catch {
      setCopied(false);
      setNote('浏览器不允许写剪贴板，请手动选中复制。');
    }
  }

  return (
    <div class="feed-body">
      <div class="kv-grid">
        <Field label="编号" value={<code>{detail.id}</code>} mono />
        <Field label="提交时间" value={fmtTime(detail.created_at)} />
        <Field label="账号（前 8 位）" value={<code>{detail.user_ref}…</code>} mono />
        <Field label="类别" value={CATEGORY_LABEL[detail.category] ?? detail.category} />
        <Field label="联系方式" value={detail.contact ? detail.contact : '未留'} />
        <Field label="邮件" value={MAIL_LABEL[mailState] ?? mailState} />
      </div>

      <h3>内容</h3>
      <pre class="pre">{detail.text || '（无文字，只有图片）'}</pre>

      {detail.images.length > 0 && (
        <>
          <h3>图片（{detail.images.length} 张，共 {fmtBytes(detail.image_bytes)}）</h3>
          <div class="thumbs">
            {detail.images.map((im) => (
              <a
                href={feedbackImageUrl(detail.id, im.index)}
                target="_blank"
                rel="noopener noreferrer"
                title={`第 ${im.index + 1} 张 · ${fmtBytes(im.bytes)}`}
              >
                <img src={feedbackImageUrl(detail.id, im.index)} alt={`反馈图片 ${im.index + 1}`} loading="lazy" />
              </a>
            ))}
          </div>
          <p class="small muted">图片在服务端已去掉元数据并重新编码；点开看原图。</p>
        </>
      )}

      <h3>诊断</h3>
      {Object.keys(detail.diag).length === 0 ? (
        <p class="small muted">没有诊断信息。</p>
      ) : (
        <table class="tbl">
          <tbody>
            {Object.entries(detail.diag).map(([k, v]) => (
              <tr>
                <th scope="row"><code>{k}</code></th>
                <td>{v}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <div class="row-actions">
        <button type="button" class="btn small" disabled={busy || detail.status === 'handled'}
                onClick={() => onStatus('handled')}>
          标记已处理
        </button>
        <button type="button" class="btn btn-ghost small" disabled={busy || detail.status === 'ignored'}
                onClick={() => onStatus('ignored')}>
          忽略
        </button>
        <button type="button" class="btn btn-ghost small" disabled={busy || detail.status === 'new'}
                onClick={() => onStatus('new')}>
          退回待处理
        </button>
        <button type="button" class="btn btn-ghost small" onClick={() => void copy()}>
          {copied ? '已复制' : '复制诊断信息'}
        </button>
      </div>
      {note && <div class="alert warn small">{note}</div>}
    </div>
  );
}
