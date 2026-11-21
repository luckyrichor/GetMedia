/** 5d：批量提取（原「博主批量」）。
 *
 * 预览走 `/v2/creators/preview`（不落库，只告诉你"会选多少条、大约多大"），
 * 建任务走 `/v2/creators/jobs`；任务由服务端跑，页面只轮询进度。
 * 抖音列表走浏览器桥接（页面自己算签名）；未配置桥接时会被 Argus 挡，页面对此给解释，不把它说成"网络问题"。
 * 条目支持直接下载到本机：服务端用扫描时存下的播放地址转发，不落盘、不经过手机。
 */
import { useEffect, useRef, useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';
import {
  creatorItemUrl, ownerApi, type CreatorItem, type CreatorJob, type CreatorPreview, type FormatKey,
  type JobStorage, type MediaType, type SelectionMode,
} from '../api';
import {
  FORMAT, FORMATS_OF, FORMAT_TIP, ITEM, JOB, SELECTION, STORAGE, STORAGE_DESC, TONE, platformName,
} from '../labels';
import { MultiSelect, Select } from '../select';
import {
  Empty, ErrorBox, fmtBytes, fmtDateTime, fmtDuration, fmtTime, Loading, relTime,
} from '../ui';

/** 三种存放方式（旧的 phone 已并入「只记清单」，不再出现在选项里）。 */
const STORAGE_OPTS: JobStorage[] = ['metadata', 'cache', 'archive'];

/** 「不再显示」只作用于这台设备的这个浏览器：任务本身还在服务端跑，只是不再占列表位置。
 *  记在 localStorage 里，刷新后依然隐藏；清站点数据或用别的设备会重新出现，所以下面留了「恢复显示」。 */
const HIDDEN_KEY = 'getmedia.creators.hiddenJobs';

function readHidden(): number[] {
  try {
    const raw = window.localStorage.getItem(HIDDEN_KEY);
    const list = raw ? (JSON.parse(raw) as unknown) : [];
    return Array.isArray(list) ? list.filter((x): x is number => typeof x === 'number') : [];
  } catch {
    return [];
  }
}

function writeHidden(ids: number[]) {
  try {
    window.localStorage.setItem(HIDDEN_KEY, JSON.stringify(ids));
  } catch {
    // 无痕模式或存储被禁用：只在本次会话里生效
  }
}

/** 输入类型：显式告诉后端这是链接、抖音号还是 B 站 UID，不再让它猜。 */
type CreatorKind = 'url' | 'douyin' | 'bilibili';

const KIND_LABEL: Record<CreatorKind, string> = {
  url: '主页链接',
  douyin: '抖音号',
  bilibili: 'B 站号',
};

const KIND_HINT: Record<CreatorKind, string> = {
  url: 'https://space.bilibili.com/12345',
  douyin: '例如 67194609689',
  bilibili: '例如 12345',
};

const URL_IN_TEXT = /https?:\/\/[^\s"'<>，。；、）】」》]+/i;
/** 只写了域名、没写 https:// 的情况（手机分享里常见）。 */
const BARE_HOST = /^(?:space\.bilibili\.com|www\.bilibili\.com|b23\.tv|www\.douyin\.com|v\.douyin\.com)\/\S*/i;

function todayISO(): string {
  const d = new Date();
  const two = (x: number) => String(x).padStart(2, '0');
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}`;
}

/** 把用户粘进来的整段内容收成「核心标识」。
 *
 * 抖音分享文案长这样：`7.53 复制打开抖音，看看… https://v.douyin.com/iRxxx/ 复制此链接…`，
 * 整段丢给后端既超长又容易带进中文标点；这里只留第一个链接，并去掉 ?/# 尾巴
 * （B 站空间、抖音主页都靠路径定位，查询串是分享埋点）。
 * 选「抖音号 / B 站号」时只认数字：先把「抖音号：123」里的数字挑出来，贴了链接就不猜，原样交给校验报错。
 */
export function normalizeCreatorInput(raw: string, kind: CreatorKind): string {
  const text = (raw || '').trim();
  if (!text) return '';
  if (kind === 'url') {
    const found = text.match(URL_IN_TEXT)?.[0].replace(/[.,;:!?)\]}>'"”’]+$/, '');
    if (!found) {
      const bare = text.match(BARE_HOST)?.[0];
      return bare ? normalizeCreatorInput(`https://${bare}`, 'url') : text.slice(0, 2000);
    }
    try {
      const u = new URL(found);
      u.search = '';
      u.hash = '';
      return u.toString();
    } catch {
      return found;
    }
  }
  if (URL_IN_TEXT.test(text)) return text.slice(0, 40);   // 贴了链接就别猜数字，交给校验
  const digits = text.match(/\d{1,20}/g);
  return digits ? digits.sort((a, b) => b.length - a.length)[0]! : text.slice(0, 40);
}

const NUMERIC_ID = /^\d{1,20}$/;

/** 点日期框直接弹日历，省得用户手敲 2026-11-21 这种格式。 */
function showDatePicker(el: HTMLInputElement) {
  const picker = (el as HTMLInputElement & { showPicker?: () => void }).showPicker;
  try {
    picker?.call(el);
  } catch {
    // 浏览器不支持或不在用户手势里时忽略，退回原生输入
  }
}

/** 任务抓的是谁：昵称取不到就说「昵称未知」，不猜也不空着。 */
function nickName(j: CreatorJob): string {
  return (j.creator_name || '').trim() || '昵称未知';
}

/** 平台号的前缀：抖音号 / B站号（连写）。后面跟带框的数字，见下方渲染。 */
const ID_LABEL: Record<string, string> = { douyin: '抖音号', bilibili: 'B站号' };

/** 用户当初填的是号码时，单独做个标签，一眼看出这批是「按号抓的」。
 *  填的是主页链接时没有号码可写（链接本身就是线索），返回空串——
 *  链接里必有 :// 或 /，`NUMERIC_ID` 本来就不认，这里再挡一次平台未知的情况。 */
function inputId(j: CreatorJob): string {
  const raw = (j.source_input || '').trim();
  return NUMERIC_ID.test(raw) ? raw : '';
}

/** 任务抓的博主号（纯数字，不带前缀）：
 *  用户填的是号码时用用户填的（用户意图，最准）；
 *  填的是链接时回退用后端解析出的平台号（creator_id）。
 *  返回空串表示这个任务没有可显示的号码。 */
function creatorId(j: CreatorJob): string {
  const input = inputId(j);
  if (input) return input;
  const id = (j.creator_id || '').trim();
  return NUMERIC_ID.test(id) ? id : '';
}

/** 号码前缀文字（「抖音号」「B站号」），无框、纯文字；没有号则空串。
 *  后面跟着一个带框的数字胶囊（见 job-head 渲染处），两者分开。 */
function creatorIdLabel(j: CreatorJob): string {
  return creatorId(j) ? (ID_LABEL[j.platform] ?? '') : '';
}

/** 这一批选了哪些作品：方式 + 条数/日期，拼成一句人能读的话。 */
function selectionText(j: CreatorJob): string {
  // 「最近 N 条」直接带数字，说成「最近 20 条」，不再「最近 N 条（20 条）」那么绕
  if (j.mode === 'latest_n') return j.limit_n ? `最近 ${j.limit_n} 条` : '最近若干条';
  let s = SELECTION[j.mode] ?? j.mode;
  if (j.since_ts) s += ` · ${fmtDay(j.since_ts)} 起`;
  if (j.until_ts) s += ` 至 ${fmtDay(j.until_ts)}`;
  return s;
}

function fmtDay(ts: number | null): string {
  if (!ts) return '';
  const d = new Date(ts * 1000);
  const two = (x: number) => String(x).padStart(2, '0');
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}`;
}

/** 'YYYY-MM-DD' → 当天 00:00:00 的 unix 秒；空串或非法返回 null（不筛）。 */
function dayStartSec(iso: string): number | null {
  const [y, m, d] = iso.split('-').map(Number);
  if (!y || !m || !d) return null;
  return Math.floor(new Date(y, m - 1, d).getTime() / 1000);
}

/** 'YYYY-MM-DD' → 当天 23:59:59 的 unix 秒（闭区间上限）；空串或非法返回 null。 */
function dayEndSec(iso: string): number | null {
  const [y, m, d] = iso.split('-').map(Number);
  if (!y || !m || !d) return null;
  return Math.floor(new Date(y, m - 1, d, 23, 59, 59).getTime() / 1000);
}

export function CreatorsPage() {
  const [kind, setKind] = useState<CreatorKind>('url');
  const [url, setUrl] = useState('');
  const [mode, setMode] = useState<SelectionMode>('latest_n');
  const [n, setN] = useState('20');
  const [since, setSince] = useState('');
  const [until, setUntil] = useState(() => todayISO());
  const [pinned, setPinned] = useState(true);
  const [storage, setStorage] = useState<JobStorage>('metadata');
  const [mediaType, setMediaType] = useState<MediaType>('video');
  // 输出格式可多选；切到音频/视频时换成那一类里最常用的那个
  const [formats, setFormats] = useState<FormatKey[]>(['source']);
  const [preview, setPreview] = useState<CreatorPreview | null>(null);
  const [busy, setBusy] = useState<'' | 'preview' | 'create' | 'act'>('');
  const [err, setErr] = useState('');
  const [jobs, setJobs] = useState<CreatorJob[]>([]);
  const [jobsPhase, setJobsPhase] = useState<'loading' | 'ok' | 'error'>('loading');
  const [jobsErr, setJobsErr] = useState('');
  const [openId, setOpenId] = useState<number | null>(null);
  const [hidden, setHidden] = useState<number[]>(() => readHidden());
  /** 刚复制过的任务 id（按钮上显示「已复制」）；0 表示没有 */
  const [copied, setCopied] = useState(0);

  // —— 批量任务筛选：每项为空 = 不筛；多项之间是「且」关系 ——
  const [fStatus, setFStatus] = useState('');
  const [fPlatform, setFPlatform] = useState('');
  const [fUid, setFUid] = useState('');
  const [fNick, setFNick] = useState('');
  const [fSince, setFSince] = useState('');
  const [fUntil, setFUntil] = useState(() => todayISO());
  const [fMedia, setFMedia] = useState('');
  const [fStorage, setFStorage] = useState('');
  const [fMode, setFMode] = useState('');

  function resetFilters() {
    setFStatus('');
    setFPlatform('');
    setFUid('');
    setFNick('');
    setFSince('');
    setFUntil(todayISO());
    setFMedia('');
    setFStorage('');
    setFMode('');
  }

  const timer = useRef<number | null>(null);

  function hideJob(id: number) {
    if (hidden.includes(id)) return;
    const next = [...hidden, id];
    setHidden(next);
    writeHidden(next);
    if (openId === id) setOpenId(null);
  }

  function showAllJobs() {
    setHidden([]);
    writeHidden([]);
  }

  /** 右键号码/昵称时自动选中这一整段文字，让浏览器右键菜单里的「复制」直接可用，
   *  不用先拖动光标去选。不阻止默认菜单，用户点菜单里的「复制」即可复制。 */
  function selectOnContextMenu(e: MouseEvent) {
    const el = e.currentTarget as HTMLElement;
    const sel = window.getSelection();
    if (!sel) return;
    const range = document.createRange();
    range.selectNodeContents(el);
    sel.removeAllRanges();
    sel.addRange(range);
  }

  async function copyText(text: string, id: number) {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(id);
      window.setTimeout(() => setCopied((cur) => (cur === id ? 0 : cur)), 1600);
    } catch {
      setErr('这个浏览器不允许自动复制，请手动选中链接复制');
    }
  }

  async function loadJobs(silent = false) {
    if (!silent) setJobsPhase('loading');
    try {
      setJobs(await ownerApi.creatorJobs());
      setJobsPhase('ok');
      setJobsErr('');
    } catch (e) {
      setJobsErr(messageFor(e));
      setJobsPhase('error');
    }
  }

  useEffect(() => {
    void loadJobs();
    return () => {
      if (timer.current) window.clearInterval(timer.current);
    };
  }, []);

  const active = jobs.some((j) => j.status === 'pending' || j.status === 'running');
  useEffect(() => {
    if (!active) {
      if (timer.current) {
        window.clearInterval(timer.current);
        timer.current = null;
      }
      return;
    }
    if (timer.current) return;
    timer.current = window.setInterval(() => void loadJobs(true), 2500);
    return () => {
      if (timer.current) {
        window.clearInterval(timer.current);
        timer.current = null;
      }
    };
  }, [active]);

  function body() {
    const value = normalizeCreatorInput(url, kind);
    return {
      url: value,
      // 链接走 resolve（由后端按域名判平台）；选「抖音号 / B 站号」时把平台显式带上，
      // 纯数字就不会被默认当成抖音号。
      platform: kind === 'url' ? null : kind,
      mode,
      n: mode === 'latest_n' ? Number(n) || null : null,
      since: mode === 'date_range' ? since || null : null,
      until: mode === 'date_range' ? until || null : null,
      include_pinned: pinned,
      formats,
      // 把用户填的原始内容一并带上（数字号 / 链接），后端存进 source_input，
      // 任务卡上才能亮出「抖音号 123」这种号；不传则后端退回用 url。
      source_input: value,
    };
  }

  function check(): boolean {
    const value = normalizeCreatorInput(url, kind);
    if (!value) {
      setErr(kind === 'url' ? '先填博主主页链接' : `先填${KIND_LABEL[kind]}`);
      return false;
    }
    // 粘贴的整段文案只留核心部分，并回填到输入框，让用户看到实际提交的是什么
    if (value !== url) setUrl(value);
    if (kind !== 'url' && !NUMERIC_ID.test(value)) {
      setErr(`${KIND_LABEL[kind]}只能填纯数字（1–20 位），不要带链接或昵称`);
      return false;
    }
    if (mode === 'latest_n' && !(Number(n) > 0)) {
      setErr('最近 N 条要填一个正整数');
      return false;
    }
    if (mode === 'date_range' && !since) {
      setErr('指定日期要至少选开始日期');
      return false;
    }
    if (mode === 'date_range' && since && until && since > until) {
      setErr('开始日期不能晚于结束日期');
      return false;
    }
    return true;
  }

  async function doPreview() {
    if (!check()) return;
    setBusy('preview');
    setErr('');
    setPreview(null);
    try {
      setPreview(await ownerApi.creatorPreview(body()));
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy('');
    }
  }

  async function doCreate() {
    if (!check()) return;
    setBusy('create');
    setErr('');
    try {
      await ownerApi.createCreatorJob({ ...body(), storage, media_type: mediaType });
      setPreview(null);
      await loadJobs(true);
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy('');
    }
  }

  async function act(id: number, action: 'pauseJob' | 'resumeJob' | 'cancelJob' | 'retryJob') {
    setBusy('act');
    setErr('');
    try {
      await ownerApi[action](id);
      await loadJobs(true);
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy('');
    }
  }

  /** 一个任务是否命中当前所有筛选条件；条件之间是「且」，空条件不参与。 */
  function matchesFilters(j: CreatorJob): boolean {
    if (fStatus && j.status !== fStatus) return false;
    if (fPlatform && j.platform !== fPlatform) return false;
    if (fUid.trim() && !creatorId(j).includes(fUid.trim())) return false;
    if (fNick.trim() && !nickName(j).includes(fNick.trim())) return false;
    const sinceSec = dayStartSec(fSince);
    if (sinceSec != null && j.created_at < sinceSec) return false;
    // 截止时间默认「今天」：按闭区间筛到今为止（真实任务都在过去，未来数据才会被它拦下）。
    const untilSec = dayEndSec(fUntil);
    if (untilSec != null && j.created_at > untilSec) return false;
    if (fMedia && j.media_type !== fMedia) return false;
    if (fStorage) {
      // 旧数据里的「手机下载」值就是 phone，行为与「只记不下载」一致、卡片上也显示「只记不下载」，
      // 所以筛「只记不下载」时两者都要命中，否则历史任务一条都筛不出来。
      if (fStorage === 'metadata') {
        if (j.storage !== 'metadata' && j.storage !== 'phone') return false;
      } else if (j.storage !== fStorage) return false;
    }
    if (fMode && j.mode !== fMode) return false;
    return true;
  }

  const shownJobs = jobs.filter((j) => !hidden.includes(j.id) && matchesFilters(j));
  // 只数当前列表里真的被隐藏的：陈旧的 id 不该让「已隐藏 N 个」虚高
  const hiddenCount = jobs.filter((j) => hidden.includes(j.id)).length;
  // 是否有任一筛选被改动（用于显示「重置」与「清空筛选」提示）
  const hasFilter = fStatus !== '' || fPlatform !== '' || fUid.trim() !== '' || fNick.trim() !== ''
    || fSince !== '' || fUntil !== todayISO() || fMedia !== '' || fStorage !== '' || fMode !== '';

  return (
    <>
      <h1>批量提取</h1>
      <ul class="small muted lead-list">
        <li>输入博主主页链接，或直接填抖音号 / B 站号。</li>
        <li>选取方式：最近 N 条、指定日期、全部作品。</li>
        <li>可以先「预览」看会选多少条，再「开始提取」。</li>
      </ul>

      <div class="panel-card">
        <div class="field-row">
          <div class="field">
            <span>类型</span>
            <Select
              ariaLabel="输入类型"
              value={kind}
              options={(Object.keys(KIND_LABEL) as CreatorKind[]).map((k) => ({ value: k, label: KIND_LABEL[k] }))}
              onChange={(v) => setKind(v as CreatorKind)}
            />
          </div>
          <label class="field">
            <span>{kind === 'url' ? '主页链接 / 分享文案' : KIND_LABEL[kind]}</span>
            <input
              type="text"
              maxLength={kind === 'url' ? 2000 : 40}
              inputMode={kind === 'url' ? 'text' : 'numeric'}
              placeholder={KIND_HINT[kind]}
              value={url}
              onInput={(e) => setUrl((e.target as HTMLInputElement).value)}
            />
          </label>
        </div>

        <div class="field-row">
          <div class="field">
            <span>选取方式</span>
            <Select
              ariaLabel="选取方式"
              value={mode}
              options={(['latest_n', 'date_range', 'all'] as SelectionMode[])
                .map((m) => ({ value: m, label: SELECTION[m] ?? m }))}
              onChange={(v) => setMode(v as SelectionMode)}
            />
          </div>
          {mode === 'latest_n' && (
            <label class="field">
              <span>条数</span>
              <input type="number" min={1} max={100000} value={n} onInput={(e) => setN((e.target as HTMLInputElement).value)} />
            </label>
          )}
          {mode === 'date_range' && (
            <>
              <label class="field">
                <span>开始日期</span>
                <input
                  type="date"
                  value={since}
                  onInput={(e) => setSince((e.target as HTMLInputElement).value)}
                  onClick={(e) => showDatePicker(e.currentTarget as HTMLInputElement)}
                />
              </label>
              <label class="field">
                <span>结束日期</span>
                <input
                  type="date"
                  value={until}
                  onInput={(e) => setUntil((e.target as HTMLInputElement).value)}
                  onClick={(e) => showDatePicker(e.currentTarget as HTMLInputElement)}
                />
              </label>
            </>
          )}
        </div>

        <div class="field-row">
          <div class="field">
            <span>存放方式</span>
            <Select
              ariaLabel="存放方式"
              value={storage}
              options={STORAGE_OPTS.map((s) => ({
                value: s, label: STORAGE[s]!, tip: STORAGE_DESC[s],
              }))}
              onChange={(v) => setStorage(v as JobStorage)}
            />
          </div>
          <div class="field">
            <span>格式</span>
            <Select
              ariaLabel="格式"
              value={mediaType}
              options={[{ value: 'video', label: '视频' }, { value: 'audio', label: '音频' }]}
              onChange={(v) => {
                setMediaType(v as MediaType);
                // 换类别时把格式收成那一类最常用的一个，免得留下不合法组合
                setFormats([(FORMATS_OF[v] ?? ['source'])[0] as FormatKey]);
              }}
            />
          </div>
          <div class="field">
            <span>输出格式</span>
            <MultiSelect
              ariaLabel="输出格式"
              values={formats}
              options={(FORMATS_OF[mediaType] ?? ['source']).map((f) => ({
                value: f, label: FORMAT[f] ?? f,
              }))}
              onChange={(v) => setFormats(v as FormatKey[])}
            />
          </div>
        </div>

        <div class="pin-row">
          <label class="check">
            <input type="checkbox" checked={pinned} onChange={(e) => setPinned((e.target as HTMLInputElement).checked)} />
            <span>包含置顶作品</span>
          </label>
          <span class="small muted">
            博主置顶的那条通常不是最新发布的，按「最近 N 条」「指定日期」筛时会被时间条件排除；
            勾上就不管时间、一起提。B 站与抖音都有置顶。
          </span>
        </div>

        <div class="row-actions">
          <button type="button" class="btn" disabled={busy !== ''} onClick={() => void doCreate()}>
            {busy === 'create' ? '正在开始…' : '开始提取'}
          </button>
          <button type="button" class="btn btn-ghost" disabled={busy !== ''} onClick={() => void doPreview()}>
            {busy === 'preview' ? '预览中…' : '预览'}
          </button>
        </div>
        {err && <div class="alert warn small">{err}</div>}
      </div>

      {preview && (
        <div class="result">
          <div class="result-head">
            <strong>{preview.profile.name}</strong>
            <span class="tag">{platformName(preview.profile.platform)}</span>
            {preview.profile.total_videos != null && <span class="tag">共 {preview.profile.total_videos} 条</span>}
            <span class="tag">{SELECTION[preview.mode] ?? preview.mode}</span>
          </div>
          <div class="result-body">
            {preview.profile.avatar_url && <img class="avatar" src={preview.profile.avatar_url} alt="" loading="lazy" />}
            <div>
              <div class="result-title">会选中 {preview.items.length} 条</div>
              <div class="small muted">
                {preview.estimated_bytes > 0 ? `预计占用 ${fmtBytes(preview.estimated_bytes)} · ` : ''}
                列表来源：{preview.cookie_source === 'configured' ? '已配置的授权' : preview.cookie_source === 'guest' ? '游客态' : '无'}
                {preview.has_more ? ' · 还有更多' : ''}
              </div>
              <ul class="plain tight">
                {preview.items.slice(0, 8).map((v) => (
                  <li>
                    <span class="ellipsis" title={v.title}>{v.title}</span>
                    <span class="small muted">　{fmtDuration(v.duration_sec)}{v.is_pinned ? ' · 置顶' : ''}</span>
                  </li>
                ))}
                {preview.items.length > 8 && <li class="small muted">……另有 {preview.items.length - 8} 条</li>}
              </ul>
            </div>
          </div>
        </div>
      )}

      <div class="jobs-head">
        <h2>批量任务</h2>
        {hasFilter && (
          <button type="button" class="btn btn-ghost small" onClick={resetFilters}>重置筛选</button>
        )}
      </div>

      {/* 筛选栏：每项空 = 不筛，多项之间是「且」；下拉选「全部」即清空单项，文本框点 × 清空 */}
      <div class="filter-bar">
        <div class="filter">
          <span>状态</span>
          <Select
            ariaLabel="筛选：状态"
            value={fStatus}
            options={[{ value: '', label: '全部状态' }].concat(
              (['pending', 'running', 'paused', 'done', 'failed', 'cancelled'] as const)
                .map((s) => ({ value: s, label: JOB[s] ?? s })),
            )}
            onChange={setFStatus}
          />
        </div>
        <div class="filter">
          <span>平台</span>
          <Select
            ariaLabel="筛选：平台"
            value={fPlatform}
            options={[
              { value: '', label: '全部平台' },
              { value: 'douyin', label: '抖音' },
              { value: 'bilibili', label: 'B 站' },
            ]}
            onChange={setFPlatform}
          />
        </div>
        <label class="filter">
          <span>用户 ID</span>
          <input
            type="text"
            inputMode="numeric"
            placeholder="输入用户 ID"
            value={fUid}
            onInput={(e) => setFUid((e.target as HTMLInputElement).value)}
          />
          {fUid && (
            <button type="button" class="filter-clear" aria-label="清除用户 ID" onClick={() => setFUid('')}>×</button>
          )}
        </label>
        <label class="filter">
          <span>昵称</span>
          <input
            type="text"
            placeholder="输入昵称"
            value={fNick}
            onInput={(e) => setFNick((e.target as HTMLInputElement).value)}
          />
          {fNick && (
            <button type="button" class="filter-clear" aria-label="清除昵称" onClick={() => setFNick('')}>×</button>
          )}
        </label>
        <div class="filter">
          <span>发起时间</span>
          <div class="filter-range">
            <input
              type="date"
              aria-label="发起时间开始"
              value={fSince}
              onInput={(e) => setFSince((e.target as HTMLInputElement).value)}
              onClick={(e) => showDatePicker(e.currentTarget as HTMLInputElement)}
            />
            <span class="muted">至</span>
            <input
              type="date"
              aria-label="发起时间截止"
              value={fUntil}
              onInput={(e) => setFUntil((e.target as HTMLInputElement).value)}
              onClick={(e) => showDatePicker(e.currentTarget as HTMLInputElement)}
            />
            {(fSince || fUntil !== todayISO()) && (
              <button
                type="button"
                class="filter-clear"
                aria-label="清除发起时间"
                onClick={() => { setFSince(''); setFUntil(todayISO()); }}
              >×</button>
            )}
          </div>
        </div>
        <div class="filter">
          <span>媒体类型</span>
          <Select
            ariaLabel="筛选：媒体类型"
            value={fMedia}
            options={[
              { value: '', label: '全部类型' },
              { value: 'video', label: '视频' },
              { value: 'audio', label: '音频' },
            ]}
            onChange={setFMedia}
          />
        </div>
        <div class="filter">
          <span>存放方式</span>
          <Select
            ariaLabel="筛选：存放方式"
            value={fStorage}
            options={[{ value: '', label: '全部存放' }].concat(
              STORAGE_OPTS.map((s) => ({ value: s, label: STORAGE[s] ?? s })),
            )}
            onChange={setFStorage}
          />
        </div>
        <div class="filter">
          <span>选取方式</span>
          <Select
            ariaLabel="筛选：选取方式"
            value={fMode}
            options={[{ value: '', label: '全部方式' }].concat(
              (['latest_n', 'date_range', 'all'] as const).map((m) => ({ value: m, label: SELECTION[m] ?? m })),
            )}
            onChange={setFMode}
          />
        </div>
      </div>

      {hiddenCount > 0 && (
        <p class="small muted">
          已隐藏 {hiddenCount} 个任务（只在这台设备的这个浏览器里）。
          <button type="button" class="btn btn-ghost small" onClick={showAllJobs}>恢复显示</button>
        </p>
      )}
      {jobsPhase === 'loading' && <Loading what="批量任务" />}
      {jobsPhase === 'error' && <ErrorBox message={jobsErr} retry={() => void loadJobs()} />}
      {jobsPhase === 'ok' && jobs.length === 0 && (
        <Empty>
          <strong>还没有批量任务</strong>
          <p class="small muted">上面填一个博主，先预览再建任务。</p>
        </Empty>
      )}
      {jobsPhase === 'ok' && jobs.length > 0 && shownJobs.length === 0 && (
        <Empty>
          <strong>{hasFilter ? '没有符合筛选条件的任务' : '任务都被隐藏了'}</strong>
          <p class="small muted">
            {hasFilter
              ? '改一下筛选条件，或点上面的「重置筛选」看全部任务。'
              : '点上面的「恢复显示」可以把它们放回来。'}
          </p>
        </Empty>
      )}
      {jobsPhase === 'ok' && shownJobs.length > 0 && (
        <ul class="jobs">
          {shownJobs.map((j, idx) => (
            <li class="job">
              <div class="job-head">
                <span class="job-no" aria-hidden="true">{idx + 1}</span>
                <span class={`tag ${TONE[j.status] ?? ''}`}>{JOB[j.status] ?? j.status}</span>
                {/* 号放昵称前面：先「抖音号」纯文字（无框），再带框的数字胶囊，两者分开；
                    有「抖音号」就知道是抖音，不再单独放平台标签 */}
                {creatorIdLabel(j) && <span class="job-id-label">{creatorIdLabel(j)}</span>}
                {creatorId(j) && (
                  <span class="tag job-id" onContextMenu={selectOnContextMenu}>{creatorId(j)}</span>
                )}
                <span class="job-who ellipsis" title={nickName(j)} onContextMenu={selectOnContextMenu}>{nickName(j)}</span>
                <span class="grow" />
                <span class="small muted nowrap" title="发起时间">
                  {relTime(j.created_at)} · {fmtDateTime(j.created_at)}
                </span>
                <button
                  type="button"
                  class="job-hide nowrap"
                  title="只在这个浏览器里不再显示这个任务；任务本身照常跑，可随时恢复"
                  onClick={() => hideJob(j.id)}
                >
                  不再显示
                </button>
              </div>
              <div class="job-src">
                {/* 平台和号码已经在上面一行了，这里只放地址本身；长链接单行显示，可以左右拖 */}
                <code class="job-url" title={j.source_url}>{j.source_url}</code>
                <button type="button" class="btn btn-ghost small nowrap"
                        onClick={() => void copyText(j.source_url, j.id)}>
                  {copied === j.id ? '已复制' : '复制链接'}
                </button>
              </div>
              <div class="job-meta small">
                <span class="meta"><b>选取</b>{selectionText(j)}</span>
                <span class="meta-sep" aria-hidden="true" />
                <span class="meta" title={STORAGE_DESC[j.storage]}>
                  <b>存放</b>{STORAGE[j.storage] ?? j.storage}
                </span>
                <span class="meta-sep" aria-hidden="true" />
                <span class="meta">
                  <b>输出</b>
                  <span class={`media-kind ${j.media_type}`}>{j.media_type === 'audio' ? '音频' : '视频'}</span>
                  <span class="format-list">
                    {(j.formats?.length ? j.formats : [FORMATS_OF[j.media_type]?.[0] ?? 'source'])
                      .map((f) => FORMAT[f] ?? f).join(' · ')}
                  </span>
                </span>
              </div>
              <div class="small">
                已抓 {j.total || '?'} / {j.total || '?'}
                {j.failed > 0 ? `（${j.failed} 下载失败）` : ''}
                {j.estimated_bytes > 0 ? ` · 约 ${fmtBytes(j.estimated_bytes)}` : ''}
                {j.pause_reason ? ` · 暂停原因：${j.pause_reason}` : ''}
              </div>
              {(j.storage === 'cache' || j.storage === 'archive') && (
                <div class="small muted">
                  已存服务器 {j.stored}{j.total ? ` / ${j.total}` : ''}
                  {j.store_bytes > 0 ? ` · ${fmtBytes(j.store_bytes)}` : ''}
                </div>
              )}
              <div class="row-actions">
                <button type="button" class="btn btn-ghost small" onClick={() => setOpenId(openId === j.id ? null : j.id)}>
                  {openId === j.id ? '收起条目' : '显示条目'}
                </button>
                {(j.status === 'pending' || j.status === 'running') && (
                  <button type="button" class="btn btn-ghost small" disabled={busy === 'act'} onClick={() => void act(j.id, 'pauseJob')}>
                    暂停
                  </button>
                )}
                {j.status === 'paused' && (
                  <button type="button" class="btn btn-ghost small" disabled={busy === 'act'} onClick={() => void act(j.id, 'resumeJob')}>
                    继续
                  </button>
                )}
                {j.status !== 'done' && j.status !== 'cancelled' && (
                  <button type="button" class="btn btn-ghost small" disabled={busy === 'act'} onClick={() => void act(j.id, 'cancelJob')}>
                    取消
                  </button>
                )}
                {/* 只有抖音需要「换地址」：直链是扫描时存下的签名地址，放久了会被 CDN 拒。
                    B 站每次下载都会现解析，这个按钮对它没有意义，所以不显示。
                    已取消（cancelled）的任务如果列表已扫完，地址同样会过期，也应能刷新地址；
                    后端会按游标判断：没扫完的取消任务点了也只按原游标续扫，不会误重扫。 */}
                {(j.status === 'done' || j.status === 'cancelled') && j.platform === 'douyin' && (
                  <button
                    type="button"
                    class="btn btn-ghost small"
                    disabled={busy === 'act'}
                    title="重新扫描一遍，把过期的播放地址换成新的"
                    onClick={() => void act(j.id, 'retryJob')}
                  >
                    刷新地址
                  </button>
                )}
                {(j.status === 'failed' || j.status === 'paused' || j.status === 'cancelled') && (
                  <button type="button" class="btn btn-ghost small" disabled={busy === 'act'} onClick={() => void act(j.id, 'retryJob')}>
                    重试失败条目
                  </button>
                )}
              </div>
              {openId === j.id && <JobItems jobId={j.id} />}
            </li>
          ))}
        </ul>
      )}
      {active && <p class="small muted">有任务在跑，每 2.5 秒自动刷新一次；全部结束后停止。</p>}
    </>
  );
}

/** 每页条数的可选值：默认 5 条，最多 20 条（再多就失去翻页的意义，也拖慢首屏）。 */
const PAGE_SIZES = [5, 10, 20];
const DEFAULT_PAGE_SIZE = 5;

/** 页码太多时只画「首、当前±1、尾」，中间用省略号，免得按钮铺满一行。 */
function pageList(cur: number, pages: number): (number | '…')[] {
  if (pages <= 7) return Array.from({ length: pages }, (_, i) => i + 1);
  const out: (number | '…')[] = [1];
  const from = Math.max(2, cur - 1);
  const to = Math.min(pages - 1, cur + 1);
  if (from > 2) out.push('…');
  for (let p = from; p <= to; p++) out.push(p);
  if (to < pages - 1) out.push('…');
  out.push(pages);
  return out;
}

function JobItems({ jobId }: { jobId: number }) {
  const [items, setItems] = useState<CreatorItem[]>([]);
  const [total, setTotal] = useState(0);
  const [phase, setPhase] = useState<'loading' | 'ok' | 'error'>('loading');
  const [err, setErr] = useState('');
  const [note, setNote] = useState('');
  const [busyId, setBusyId] = useState('');
  /** 翻页：第几页（从 1 起）与每页条数。只看这一页，不再「加载更多」累加。 */
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(DEFAULT_PAGE_SIZE);
  /** 正在翻页：旧表格半透明过渡（不抽掉、不插占位，避免高度跳变抖动）。 */
  const [switching, setSwitching] = useState(false);
  /** 批量下载的进度；null 表示没在跑。stop 是给循环看的「立即停」标志。 */
  const [run, setRun] = useState<{ done: number; total: number } | null>(null);
  const stopRef = useRef(false);

  const pages = Math.max(1, Math.ceil(total / size));

  async function load(p: number, s: number, opts?: { silent?: boolean }) {
    // 首次打开仍走整块 loading；翻页/换每页条数时 silent，保持旧表格原位，等新数据到了再换
    if (!opts?.silent) setPhase('loading');
    else setSwitching(true);
    try {
      const out = await ownerApi.creatorItems(jobId, (p - 1) * s, s);
      // 条目可能被别处改动（重试后变少），页码越界就退回最后一页
      const last = Math.max(1, Math.ceil(out.total / s));
      if (p > last) {
        setPage(last);
        return;
      }
      setItems(out.items);
      setTotal(out.total);
      setPhase('ok');
    } catch (e) {
      setErr(messageFor(e));
      setPhase('error');
    } finally {
      setSwitching(false);
    }
  }

  useEffect(() => {
    void load(page, size, { silent: phase === 'ok' });
  }, [jobId, page, size]);

  if (phase === 'error') return <ErrorBox message={err} retry={() => void load(page, size)} />;
  if (phase === 'loading') return <Loading what="任务条目" />;

  const sleep = (ms: number) => new Promise<void>((ok) => setTimeout(ok, ms));

  /** 把任务的全部条目取回来（内部按 100 条一页拉，跟界面上的每页条数无关）：
   *  「下载全部」必须覆盖全部，不能只下当前这一页。 */
  async function fetchAll(): Promise<CreatorItem[]> {
    const acc: CreatorItem[] = [];
    let guard = 0;
    for (;;) {
      const out = await ownerApi.creatorItems(jobId, acc.length, 100);
      acc.push(...out.items);
      setTotal(out.total);
      guard += 1;
      if (out.items.length === 0 || acc.length >= out.total || guard > 100) break;
    }
    return acc;
  }

  /** 先探 2 个字节：能出流才真正下载。
   *  这样直链过期时不会把一段 JSON 错误存成 .mp4，也能区分「该换地址」和「真没了」。 */
  async function probe(url: string): Promise<{ ok: boolean; status: number; detail: string }> {
    try {
      const r = await fetch(url, { headers: { Range: 'bytes=0-1' }, credentials: 'same-origin' });
      if (r.ok) {
        // 只读了 2 个字节，别把整条流留在内存里
        void r.body?.cancel().catch(() => undefined);
        return { ok: true, status: r.status, detail: '' };
      }
      // 错误响应本身就是 JSON，json() 读完即结束，不要再 cancel（已消费的流会抛 TypeError）
      const body = (await r.json().catch(() => null)) as { detail?: string } | null;
      return { ok: false, status: r.status, detail: body?.detail || `HTTP ${r.status}` };
    } catch (e) {
      return { ok: false, status: 0, detail: messageFor(e) };
    }
  }

  function save(url: string) {
    const a = document.createElement('a');
    a.href = url;
    a.download = '';
    document.body.appendChild(a);
    a.click();
    a.remove();
  }

  /** 下一条。返回 false 表示这条没成（403/409/网络），由调用方决定是跳过还是停。 */
  async function one(item: CreatorItem, fmt?: string): Promise<boolean> {
    const url = creatorItemUrl(jobId, item.video_id, fmt);
    setBusyId(item.video_id);
    try {
      const r = await probe(url);
      if (r.ok) {
        save(url);
        return true;
      }
      // 409 的文案由服务端给（三种情形：等 1 分钟重试 / 正在重新生成 / 请换新地址），
      // 前端不自己拼，免得两边不一致
      setNote(r.status === 409
        ? `「${item.title}」${r.detail}`
        : `「${item.title}」下载失败：${r.detail}`);
      return false;
    } finally {
      setBusyId('');
    }
  }

  async function downloadAll() {
    setNote('');
    let all = items;
    if (items.length < total) {
      setNote('正在取回全部条目…');
      try {
        all = await fetchAll();
      } catch (e) {
        setNote(`取回条目失败：${messageFor(e)}`);
        return;
      }
      setNote('');
    }
    if (!all.length) return;
    if (!confirm(`要把这 ${all.length} 条逐个下载到本机吗？\n\n浏览器第一次会询问"是否允许下载多个文件"，请选允许。\n下载过程中可以随时点「停止下载」。`)) return;

    stopRef.current = false;
    setRun({ done: 0, total: all.length });
    let failed = 0;
    let started = 0;
    for (let i = 0; i < all.length; i++) {
      if (stopRef.current) break;
      const ok = await one(all[i] as CreatorItem);
      if (ok) started += 1;
      else failed += 1;
      setRun({ done: i + 1, total: all.length });
      if (stopRef.current) break;
      // 拉开间隔：连续触发下载会被浏览器当成弹窗滥用，直接拦掉
      if (i < all.length - 1) await sleep(1200);
    }
    const stopped = stopRef.current;
    setRun(null);
    setNote(`${stopped ? '已停止' : '已跑完'}：${started} 条开始下载，共 ${all.length} 条`
      + `${failed ? `，${failed} 条取不到流` : ''}。`);
  }

  return (
    <div class={switching ? 'items switching' : 'items'}>
      {items.length > 0 && (
        <div class="items-tools">
          {run ? (
            <div class="row-actions">
              <button type="button" class="btn btn-ghost small" onClick={() => { stopRef.current = true; }}>
                停止下载
              </button>
              <span class="small muted">正在下载 {run.done} / {run.total}（点「停止下载」立即收手，已开始的这条会下完）</span>
            </div>
          ) : (
            <div class="row-actions">
              <button type="button" class="btn btn-ghost small" disabled={busyId !== ''} onClick={() => void downloadAll()}>
                下载全部（{total || items.length} 条）
              </button>
            </div>
          )}
          <div class="page-size">
            <span class="small muted">每页</span>
            <Select
              ariaLabel="每页条数"
              value={String(size)}
              options={PAGE_SIZES.map((n) => ({ value: String(n), label: `${n} 条` }))}
              onChange={(v) => {
                // 换每页条数后回到第一页，不然页码会指到别的内容上
                setSize(Number(v));
                setPage(1);
              }}
            />
            <span class="small muted">共 {total} 条</span>
          </div>
        </div>
      )}
      {note && <p class="small muted">{note}</p>}
      <div class="item-head small muted">
        <span class="item-no">序号</span>
        <span>标题</span>
        <span>时长</span>
        <span>发布</span>
        <span>提取</span>
        <span />
      </div>
      <ul class="plain tight">
        {items.map((i, idx) => (
          <li class="item-row">
            {/* 序号接着上一页往下数，翻页后也知道这是第几条 */}
            <span class="item-no" aria-hidden="true">{(page - 1) * size + idx + 1}</span>
            <span class="item-title">
              <span class="ellipsis" title={i.title}>{i.title}</span>
              {/* 手机下载的任务里服务器只记账、不下载，pending 会一直是 pending——所以「待处理」不显示，
                  只有真落了盘或失败才有标记。标记放在标题右边，标题的起点才不会因它左右跳。 */}
              {i.stored && <span class="tag ok" title="服务器上已经有一份，点下载直接从服务器取">已在服务器</span>}
              {!i.stored && i.download_state === 'failed' && <span class="tag bad">失败</span>}
            </span>
            <span class="small muted nowrap">{fmtDuration(i.duration_sec)}</span>
            <span class="small muted nowrap">{i.published_at ? fmtTime(i.published_at) : '—'}</span>
            <span class="small muted nowrap">{fmtTime(i.stored_at ?? i.added_at)}</span>
            <span class="item-act">
              {Object.keys(i.files ?? {}).map((f) => (
                <button
                  type="button"
                  class="fmt-chip"
                  title={`下载 ${FORMAT[f] ?? f} 这一版`}
                  disabled={busyId !== '' || run !== null}
                  onClick={() => void one(i, f)}
                >
                  {FORMAT[f] ?? f}
                </button>
              ))}
              <button
                type="button"
                class="btn btn-ghost small nowrap"
                disabled={busyId === i.video_id || run !== null}
                onClick={() => void one(i)}
              >
                {busyId === i.video_id ? '检查中…' : '下载'}
              </button>
            </span>
          </li>
        ))}
      </ul>
      {items.some((i) => i.error) && (
        <p class="small bad-text">失败原因示例：{items.find((i) => i.error)?.error}</p>
      )}
      {pages > 1 && (
        <nav class="pager" aria-label="条目分页">
          <button
            type="button"
            class="btn btn-ghost small"
            disabled={page <= 1}
            onClick={() => setPage(page - 1)}
          >
            上一页
          </button>
          {pageList(page, pages).map((p) => (
            p === '…'
              ? <span class="pager-gap" aria-hidden="true">…</span>
              : (
                <button
                  type="button"
                  class={p === page ? 'btn small pager-on' : 'btn btn-ghost small'}
                  aria-current={p === page ? 'page' : undefined}
                  onClick={() => setPage(p)}
                >
                  {p}
                </button>
              )
          ))}
          <button
            type="button"
            class="btn btn-ghost small"
            disabled={page >= pages}
            onClick={() => setPage(page + 1)}
          >
            下一页
          </button>
          <span class="small muted">第 {page} / {pages} 页</span>
        </nav>
      )}
    </div>
  );
}
