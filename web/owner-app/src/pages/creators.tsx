/** 5d：批量提取（原「博主批量」）。
 *
 * 预览走 `/v2/creators/preview`（不落库，只告诉你"会选多少条、大约多大"），
 * 建任务走 `/v2/creators/jobs`；任务由服务端跑，页面只轮询进度。
 * 抖音列表走浏览器桥接（页面自己算签名）；未配置桥接时会被 Argus 挡，页面对此给解释，不把它说成"网络问题"。
 * 条目支持直接下载到本机：服务端用扫描时存下的播放地址转发，不落盘、不经过手机。
 *
 * 页面自上而下：标题 + 一行提示 → 提取表单 → 「批量任务」标题（右侧共 N 条）→
 * 筛选栏（两行）→ 任务表格卡片 + 底部「共 N 条」。视觉规范与「权限管理」「邀请码」同一套，
 * 见 styles.css 的「批量提取页」段。
 */
import { Fragment } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';
import {
  creatorItemUrl, ownerApi, type CreatorItem, type CreatorJob, type CreatorPreview, type FormatKey,
  type JobStorage, type MediaType, type SelectionMode,
} from '../api';
import {
  FORMAT, FORMATS_OF, FORMAT_TIP, JOB, SELECTION, STORAGE, STORAGE_DESC, platformName,
} from '../labels';
import { MultiSelect, Select } from '../select';
import {
  BytesCell, DateInput, DateRange, DateTimeCell, ErrorBox, Loading, Menu, Segmented, Stepper,
  dayEndSec, dayStartSec,
  fmtBytes, fmtDateTime, fmtDuration, fmtTime, relTime, useToast, type MenuItem,
} from '../ui';

/** 三种存放方式（旧的 phone 已并入「只记清单」，不再出现在选项里）。 */
const STORAGE_OPTS: JobStorage[] = ['metadata', 'cache', 'archive'];

/** 「不再显示」只作用于这台设备的这个浏览器：任务本身还在服务端跑，只是不再占列表位置。
 *  记在 localStorage 里，刷新后依然隐藏；清站点数据或用别的设备会重新出现，
 *  所以隐藏后 toast 里留一个「撤销」，列表底部也留了「恢复显示」。 */
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

/** 输入类型：选的是**平台**，号码和主页链接两种写法都收。
 *
 *  B 站号是纯数字 UID；抖音号可能是字母串（`chenling_hana`）。两边的主页链接
 *  （`space.bilibili.com/12345`、`douyin.com/user/MS4wLjAB…`）都直接交给服务端解析——
 *  短链展开、sec_uid 提取是 `creator_links.py` 的事，前端只负责认出是哪个平台。
 */
type CreatorKind = 'douyin' | 'bilibili';

const KIND_LABEL: Record<CreatorKind, string> = {
  douyin: '抖音',
  bilibili: 'B 站',
};

/** 输入框上方那行字：把「两种写法」写在标签上，比藏在 placeholder 里显眼。 */
const KIND_FIELD: Record<CreatorKind, string> = {
  douyin: '抖音号 / 主页链接',
  bilibili: 'B 站号 / 主页链接',
};

const KIND_HINT: Record<CreatorKind, string> = {
  douyin: '例如 67194609689、luoyonghao，或粘贴 douyin.com/user/… 的主页链接',
  bilibili: '例如 12345，或粘贴 space.bilibili.com/12345',
};

/** 校验不过时说的话。两个平台的「号」不是一回事：B 站 UID 只能是数字，
 *  抖音号可以是字母（`luoyonghao`）——**字母号也是号，照常按号查**，
 *  不能因为不是数字就说人「只能填数字」。 */
const KIND_TIP: Record<CreatorKind, string> = {
  douyin: '只能填抖音号（数字或字母），或粘贴抖音主页链接（douyin.com/user/…）',
  bilibili: '只能填纯数字的 B 站号，或粘贴 space.bilibili.com/号',
};

/** 两个平台「号」的字符集：B 站是纯数字 UID，抖音号允许字母/下划线/点/短横线。 */
const ID_PATTERN: Record<CreatorKind, RegExp> = {
  douyin: /^[A-Za-z0-9_.-]{1,30}$/,
  bilibili: /^\d{1,20}$/,
};

const URL_IN_TEXT = /https?:\/\/[^\s"'<>，。；、）】」》]+/i;
/** 没写协议的域名也当链接看：从地址栏复制常常只剩 `space.bilibili.com/12345`。 */
const BARE_HOST = /^(?:[a-z0-9-]+\.)+(?:com|cn|tv|net)(?:\/|$)/i;
/** 链接尾巴上的标点不算链接的一部分（中文标点紧跟在 URL 后面时很常见）。 */
const TRAILING_PUNCT = /[.,;:!?）】」》"'”’。，、；：！？]+$/;

const NUMERIC_ID = /^\d{1,20}$/;

/** 链接属于哪个平台：只看域名，不看路径。
 *
 *  路径能不能解析出博主（作品链接 vs 主页链接）由服务端判断——那边有短链展开和
 *  更准的话术，前端在这里猜只会猜错。认不出平台返回 null。 */
export function platformOfLink(text: string): CreatorKind | null {
  const hit = text.match(URL_IN_TEXT)?.[0] ?? (BARE_HOST.test(text.trim()) ? text.trim() : '');
  if (!hit) return null;
  let host = '';
  try {
    host = new URL(/^https?:\/\//i.test(hit) ? hit : `https://${hit}`).hostname.toLowerCase();
  } catch {
    return null;
  }
  if (host === 'bilibili.com' || host.endsWith('.bilibili.com')
    || host === 'b23.tv' || host === 'bili2233.cn') return 'bilibili';
  if (host === 'douyin.com' || host.endsWith('.douyin.com')
    || host === 'iesdouyin.com' || host.endsWith('.iesdouyin.com')) return 'douyin';
  return null;
}


function todayISO(): string {
  const d = new Date();
  const two = (x: number) => String(x).padStart(2, '0');
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}`;
}

/** 把用户粘进来的内容收成「要提交的东西」。
 *
 * 三种写法：纯数字号、主页链接（含整段分享文案里夹着的那一条）、带前缀的号码
 * （`抖音号：123`）。**有链接就交链接**——短链展开和 sec_uid 提取都在服务端，
 * 前端只把它从文案里抠出来、补上协议、去掉尾随标点；截断会把链接截坏，所以不截。
 */
export function normalizeCreatorInput(raw: string): string {
  const text = (raw || '').trim();
  if (!text) return '';
  const url = text.match(URL_IN_TEXT)?.[0]?.replace(TRAILING_PUNCT, '');
  if (url) return url;
  if (BARE_HOST.test(text)) return `https://${text.replace(TRAILING_PUNCT, '')}`;
  // 整段就是一个号（含字母号 `luoyonghao`）就原样收：先挑数字会把
  // `chenling2024` 这类号截成 `2024`，号就变了。
  if (ID_PATTERN.douyin.test(text) || ID_PATTERN.bilibili.test(text)) return text;
  const digits = text.match(/\d{1,20}/g);
  if (digits) return digits.sort((a, b) => b.length - a.length)[0]!;
  return text.slice(0, 80);
}

/** 号码或主页链接都能提交；其它输入当场说清楚错在哪，不浪费一次请求。
 *
 * 最容易贴错的是**作品链接**（那是「单个提取」页的事），所以单独给一句能指路的话。 */
function inputError(value: string, kind: CreatorKind): string | null {
  if (!value) return `先填${KIND_FIELD[kind]}`;
  if (ID_PATTERN[kind].test(value)) return null;
  const linkKind = platformOfLink(value);
  if (!linkKind) return KIND_TIP[kind];
  if (linkKind !== kind) return `这是${KIND_LABEL[linkKind]}的链接，把上面的类型改成${KIND_LABEL[linkKind]}`;
  if (linkKind === 'bilibili' && /\/video\/|BV[0-9A-Za-z]{10}|\/av\d+/i.test(value)) {
    return '这是单个视频的链接；要抓整个 UP 主请贴空间主页（space.bilibili.com/号），单条视频请用「单个提取」';
  }
  if (linkKind === 'douyin' && /\/(?:video|note|share\/video)\//.test(value)) {
    return '这是单个作品的链接；要抓整个博主请贴主页（douyin.com/user/…），单条作品请用「单个提取」';
  }
  return null;
}

/** 条数与起始序号的上限：只在输入校验和步进按钮上用，不改变服务端行为。 */
const MAX_N = 100000;
const MAX_START = 500;

/** 任务抓的是谁：昵称取不到就说「昵称未知」，不猜也不空着。 */
function nickName(j: CreatorJob): string {
  return (j.creator_name || '').trim() || '昵称未知';
}

/** 用户当初填的是号码时，用用户填的（用户意图，最准）；
 *  填的是链接时回退用后端解析出的平台号（creator_id）。返回空串表示没有可显示的号码。 */
function creatorId(j: CreatorJob): string {
  const raw = (j.source_input || '').trim();
  // 用户填的是号（数字或字母都算）就原样亮出来；填的是链接才回退到平台解析出的 id
  if (raw && !URL_IN_TEXT.test(raw) && !BARE_HOST.test(raw)) return raw;
  const id = (j.creator_id || '').trim();
  return NUMERIC_ID.test(id) ? id : '';
}

/** 这一批选了哪些作品：方式 + 条数/日期，拼成一句人能读的话（完整版，进 tooltip）。 */
function selectionText(j: CreatorJob): string {
  if (j.mode === 'latest_n') return j.limit_n ? `最近 ${j.limit_n} 条` : '最近若干条';
  if (j.mode === 'from_start') {
    const dir = j.direction === 'newer' ? '往最新' : '往更久';
    return j.start_n ? `从第 ${j.start_n} 条${dir}` : '指定起点';
  }
  let s = SELECTION[j.mode] ?? j.mode;
  if (j.since_ts) s += ` · ${fmtDay(j.since_ts)} 起`;
  if (j.until_ts) s += ` 至 ${fmtDay(j.until_ts)}`;
  return s;
}

/** 表格里那一格放得下的短写法（「选取」列只有 104px，完整内容在 tooltip 里）。
 *  日期范围缩成「月-日~月-日」，年份不进这一格；起点去掉开头的「从」，省两个字。 */
function selectionShort(j: CreatorJob): string {
  if (j.mode === 'date_range') {
    const a = j.since_ts ? md(j.since_ts) : '';
    const b = j.until_ts ? md(j.until_ts) : '';
    if (a && b) return `${a} ~ ${b}`;
    if (a) return `${a} 起`;
    if (b) return `至 ${b}`;
  }
  if (j.mode === 'from_start' && j.start_n) {
    return `第 ${j.start_n} 条${j.direction === 'newer' ? '往最新' : '往更久'}`;
  }
  return selectionText(j);
}

function fmtDay(ts: number | null): string {
  if (!ts) return '';
  const d = new Date(ts * 1000);
  const two = (x: number) => String(x).padStart(2, '0');
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}`;
}

/** `MM-DD`：日期范围在窄列里只留月日，年份进 tooltip。 */
function md(ts: number): string {
  const d = new Date(ts * 1000);
  const two = (x: number) => String(x).padStart(2, '0');
  return `${two(d.getMonth() + 1)}-${two(d.getDate())}`;
}

/** 状态 → 淡色标签的色调：已完成绿、已取消灰、进行中蓝、失败红、已暂停黄。 */
const STATUS_TONE: Record<string, string> = {
  pending: 'info',
  running: 'info',
  paused: 'warn',
  done: 'ok',
  failed: 'bad',
  cancelled: 'idle',
};

/** 表格列。宽度由 styles.css 的 `--c-*` 变量给，`<col style="width: var(--c-*)">` 引用；
 *  「博主」那列不写宽度，`table-layout: fixed` 下它会吃掉剩余宽度（唯一会伸的那一列）。 */
const COLS: { label: string; cls: string; mid?: boolean; tip?: string }[] = [
  { label: '序号', cls: 'c-no' },
  { label: '博主', cls: 'c-who' },
  { label: '状态', cls: 'c-st' },
  { label: '选取', cls: 'c-sel' },
  { label: '存放与输出', cls: 'c-out' },
  { label: '进度', cls: 'c-prog', mid: true },
  { label: '占用', cls: 'c-bytes', mid: true, tip: '按作品平均码率估算，实际以服务器上的文件为准' },
  { label: '发起时间', cls: 'c-at' },
  { label: '操作', cls: 'c-ops' },
];

/** 表单校验结果：字段名 → 红字。空对象表示通过。 */
type ErrMap = { url?: string; n?: string; start?: string; date?: string };

export function CreatorsPage() {
  const { show: toast, node: toastNode } = useToast();

  // —— 提取表单 ——
  const [kind, setKind] = useState<CreatorKind>('douyin');
  const [url, setUrl] = useState('');
  const [mode, setMode] = useState<SelectionMode>('latest_n');
  const [n, setN] = useState('20');
  const [since, setSince] = useState('');
  const [until, setUntil] = useState(() => todayISO());
  const [startNo, setStartNo] = useState('10');
  const [direction, setDirection] = useState<'newer' | 'older'>('older');
  const [pinned, setPinned] = useState(true);
  const [storage, setStorage] = useState<JobStorage>('metadata');
  const [mediaType, setMediaType] = useState<MediaType>('video');
  // 输出格式可多选；切到音频/视频时换成那一类里最常用的那个（两类没有交集，不会留下无效组合）
  const [formats, setFormats] = useState<FormatKey[]>(['source']);
  const [preview, setPreview] = useState<CreatorPreview | null>(null);
  const [previewErr, setPreviewErr] = useState('');
  const [errs, setErrs] = useState<ErrMap>({});
  const [busy, setBusy] = useState<'' | 'preview' | 'create' | 'act'>('');

  // —— 任务列表 ——
  const [jobs, setJobs] = useState<CreatorJob[]>([]);
  const [jobsPhase, setJobsPhase] = useState<'loading' | 'ok' | 'error'>('loading');
  const [jobsErr, setJobsErr] = useState('');
  const [openId, setOpenId] = useState<number | null>(null);
  const [hidden, setHidden] = useState<number[]>(() => readHidden());
  /** 刚建好的任务 id（那一行高亮 2 秒）；0 表示没有 */
  const [fresh, setFresh] = useState(0);

  // —— 批量任务筛选：每项为空 = 不筛；多项之间是「且」关系 ——
  const [fStatus, setFStatus] = useState('');
  const [fNick, setFNick] = useState('');
  const [fUid, setFUid] = useState('');
  const [fPlatform, setFPlatform] = useState('');
  const [fMedia, setFMedia] = useState('');
  const [fStorage, setFStorage] = useState('');
  const [fMode, setFMode] = useState('');
  const [fSince, setFSince] = useState('');
  /** 发起时间两端默认都为空（空 = 不筛），只填一端也生效 */
  const [fUntil, setFUntil] = useState('');

  const timer = useRef<number | null>(null);
  const freshTimer = useRef<number | undefined>(undefined);

  useEffect(() => () => {
    if (freshTimer.current !== undefined) window.clearTimeout(freshTimer.current);
  }, []);

  /** 表单一改动，上一条预览就作废：留着一条跟当前条件对不上的数字比没有更糟。 */
  function clearPreview() {
    setPreview(null);
    setPreviewErr('');
  }

  function hideJob(j: CreatorJob) {
    if (hidden.includes(j.id)) return;
    const next = [...hidden, j.id];
    setHidden(next);
    writeHidden(next);
    if (openId === j.id) setOpenId(null);
    // 只是"在这台设备上不显示"，任务照常在服务端跑 —— 所以不弹确认框，给一次撤销就够
    toast('已从列表隐藏，任务仍在跑', 'ok', {
      label: '撤销',
      onClick: () => {
        const back = readHidden().filter((x) => x !== j.id);
        setHidden(back);
        writeHidden(back);
      },
    });
  }

  function showAllJobs() {
    setHidden([]);
    writeHidden([]);
  }

  async function copyText(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      toast('已复制');
    } catch {
      toast('这个浏览器不允许自动复制，请手动选中链接复制', 'err');
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

  /** 选取条件：预览与建任务共用。 */
  function selectBody() {
    return {
      url: normalizeCreatorInput(url),
      // 平台显式带上：纯数字本身看不出是抖音号还是 B 站号。
      platform: kind,
      mode,
      n: mode === 'latest_n' ? Number(n) || null : null,
      since: mode === 'date_range' ? since || null : null,
      until: mode === 'date_range' ? until || null : null,
      start_n: mode === 'from_start' ? Number(startNo) || null : null,
      direction: mode === 'from_start' ? direction : null,
      include_pinned: pinned,
    };
  }

  /** 预览请求体。
   *
   * 预览接口（``CreatorPreviewBody``）是 ``extra="forbid"``：只认选取参数，
   * 多一个字段就整单 422（页面上的「提交的内容格式不正确」）。
   * 所以这里绝不能带 ``formats`` / ``source_input`` / ``storage`` 这些
   * 只有建任务才用的字段——它们是以前混用同一个 body 时撞出来的 bug。
   */
  function previewBody() {
    return { ...selectBody(), media_type: mediaType };
  }

  /** 建任务请求体：选取条件 + 存放方式 + 输出格式 + 原始输入。 */
  function jobBody() {
    const b = selectBody();
    return {
      ...b,
      storage,
      media_type: mediaType,
      formats,
      // 把用户填的原始内容一并带上（数字号 / 链接），后端存进 source_input，
      // 任务行上才能亮出「抖音号 123」这种号；不传则后端退回用 url。
      source_input: b.url,
    };
  }

  /** 校验：所有错误一起标出来（只报第一条会让人来回试），有错就一个请求都不发。 */
  function validate(): boolean {
    const next: ErrMap = {};
    const value = normalizeCreatorInput(url);
    const inputErr = inputError(value, kind);
    if (inputErr) next.url = inputErr;
    if (mode === 'latest_n') {
      const v = Number(n);
      if (!/^\d+$/.test(n.trim()) || v < 1 || v > MAX_N) next.n = `条数要是 1–${MAX_N} 之间的整数`;
    }
    if (mode === 'from_start') {
      const v = Number(startNo);
      if (!/^\d+$/.test(startNo.trim()) || v < 1 || v > MAX_START) {
        next.start = `起始序号要是 1–${MAX_START} 之间的整数`;
      }
    }
    if (mode === 'date_range') {
      if (!since) next.date = '指定日期要至少选开始日期';
      else if (until && since > until) next.date = '开始日期不能晚于结束日期';
    }
    setErrs(next);
    return Object.keys(next).length === 0;
  }

  async function doPreview() {
    if (!validate()) return;
    // 粘贴的整段文案只留核心部分，并回填到输入框，让用户看到实际提交的是什么
    const value = normalizeCreatorInput(url);
    if (value !== url) setUrl(value);
    setBusy('preview');
    clearPreview();
    try {
      setPreview(await ownerApi.creatorPreview(previewBody()));
    } catch (e) {
      setPreviewErr(messageFor(e));
    } finally {
      setBusy('');
    }
  }

  async function doCreate() {
    if (!validate()) return;
    const value = normalizeCreatorInput(url);
    if (value !== url) setUrl(value);
    setBusy('create');
    clearPreview();
    try {
      const job = await ownerApi.createCreatorJob(jobBody());
      await loadJobs(true);
      toast('任务已创建');
      setFresh(job.id);
      if (freshTimer.current !== undefined) window.clearTimeout(freshTimer.current);
      freshTimer.current = window.setTimeout(() => setFresh(0), 2000);
      // 新任务可能落在列表中间：不在视口里就滚过去，别让人自己找
      window.setTimeout(() => {
        const el = document.querySelector<HTMLElement>(`[data-job="${job.id}"]`);
        if (!el || typeof el.scrollIntoView !== 'function') return;
        const r = el.getBoundingClientRect();
        if (r.top < 60 || r.bottom > window.innerHeight - 8) {
          el.scrollIntoView({ block: 'center', behavior: 'smooth' });
        }
      }, 80);
    } catch (e) {
      // 失败时保留已填内容，改一下就能重试
      toast(messageFor(e), 'err');
    } finally {
      setBusy('');
    }
  }

  async function act(id: number, action: 'pauseJob' | 'resumeJob' | 'cancelJob' | 'retryJob') {
    setBusy('act');
    try {
      await ownerApi[action](id);
      await loadJobs(true);
    } catch (e) {
      toast(messageFor(e), 'err');
    } finally {
      setBusy('');
    }
  }

  function resetFilters() {
    setFStatus('');
    setFNick('');
    setFUid('');
    setFPlatform('');
    setFMedia('');
    setFStorage('');
    setFMode('');
    setFSince('');
    setFUntil('');
  }

  /** 一个任务是否命中当前所有筛选条件；条件之间是「且」，空条件不参与。 */
  function matchesFilters(j: CreatorJob): boolean {
    if (fStatus && j.status !== fStatus) return false;
    if (fPlatform && j.platform !== fPlatform) return false;
    if (fUid.trim() && !creatorId(j).includes(fUid.trim())) return false;
    if (fNick.trim() && !nickName(j).includes(fNick.trim())) return false;
    const sinceSec = dayStartSec(fSince);
    if (sinceSec != null && j.created_at < sinceSec) return false;
    const untilSec = dayEndSec(fUntil);
    if (untilSec != null && j.created_at > untilSec) return false;
    if (fMedia && j.media_type !== fMedia) return false;
    if (fStorage) {
      // 旧数据里的「手机下载」值就是 phone，行为与「只存元数据」一致、行里也显示「只存元数据」，
      // 所以筛「只存元数据」时两者都要命中，否则历史任务一条都筛不出来。
      if (fStorage === 'metadata') {
        if (j.storage !== 'metadata' && j.storage !== 'phone') return false;
      } else if (j.storage !== fStorage) return false;
    }
    if (fMode && j.mode !== fMode) return false;
    return true;
  }

  /** 隐藏的只数当前列表里真的存在的：陈旧的 id 不该让「已隐藏 N 个」虚高。 */
  const visible = jobs.filter((j) => !hidden.includes(j.id));
  const shownJobs = visible.filter(matchesFilters);
  const hiddenCount = jobs.length - visible.length;
  const hasFilter = fStatus !== '' || fPlatform !== '' || fUid.trim() !== '' || fNick.trim() !== ''
    || fSince !== '' || fUntil !== '' || fMedia !== '' || fStorage !== '' || fMode !== '';

  /** 状态分段的每段数量：任务是一次全量拉回来的，所以这些数字是准的。 */
  const statusCount = (s: string) => visible.filter((j) => j.status === s).length;

  /** 行内操作：两个高频动作留在外面，其余收进「⋯」。 */
  function rowOps(j: CreatorJob) {
    const more: MenuItem[] = [];
    if (j.status === 'pending' || j.status === 'running') {
      more.push({ text: '暂停', onClick: () => void act(j.id, 'pauseJob') });
    }
    if (j.status === 'paused') {
      more.push({ text: '继续', onClick: () => void act(j.id, 'resumeJob') });
    }
    if (j.status !== 'done' && j.status !== 'cancelled') {
      more.push({ text: '取消任务', danger: true, onClick: () => void act(j.id, 'cancelJob') });
    }
    // 只有真有失败条目、且这一批已经停下来时才给「重试」（跑着的任务由服务端自己重试）
    if (j.failed > 0 && (j.status === 'failed' || j.status === 'paused' || j.status === 'cancelled')) {
      more.push({ text: '重试失败条目', onClick: () => void act(j.id, 'retryJob') });
    }
    more.push({ text: '复制链接', onClick: () => void copyText(j.source_url) });
    more.push({ text: '不再显示', danger: true, divider: true, onClick: () => hideJob(j) });

    return (
      <div class="row-ops">
        <button
          type="button"
          class="text-btn"
          aria-expanded={openId === j.id}
          onClick={() => setOpenId(openId === j.id ? null : j.id)}
        >
          {openId === j.id ? '收起条目 ▴' : '显示条目'}
        </button>
        {/* 只有抖音需要「换地址」：直链是扫描时存下的签名地址，放久了会被 CDN 拒。
            B 站每次下载都会现解析，这个按钮对它没有意义，所以不显示。
            只给「已完成」：已取消的任务不在下载，地址过期无所谓；而且以前对已取消的
            任务点它会把任务标成「已完成」，落盘阶段却一条都没跑。 */}
        {j.status === 'done' && j.platform === 'douyin' ? (
          <button
            type="button"
            class="text-btn"
            disabled={busy === 'act'}
            title="重新扫描一遍，把过期的播放地址换成新的"
            onClick={() => void act(j.id, 'retryJob')}
          >
            刷新地址
          </button>
        ) : null}
        <Menu variant="icon" ariaLabel="更多操作" align="right" disabled={busy === 'act'} items={more} />
      </div>
    );
  }

  return (
    <>
      {toastNode}

      <div class="page-head">
        <h1>批量提取</h1>
      </div>

      {/* ---- 提取表单 ---- */}
      <div class="ext-form">
        <div class="ext-row">
          <div class="field ext-kind">
            <span>类型</span>
            <Select
              ariaLabel="输入类型"
              value={kind}
              options={(Object.keys(KIND_LABEL) as CreatorKind[]).map((k) => ({ value: k, label: KIND_LABEL[k] }))}
              onChange={(v) => { setKind(v as CreatorKind); setErrs({}); clearPreview(); }}
            />
          </div>
          <label class={errs.url ? 'field ext-url is-bad' : 'field ext-url'}>
            <span>{KIND_FIELD[kind]}</span>
            <input
              type="text"
              maxLength={200}
              placeholder={KIND_HINT[kind]}
              aria-label={KIND_FIELD[kind]}
              value={url}
              onInput={(e) => {
                const v = (e.target as HTMLInputElement).value;
                setUrl(v);
                setErrs({});
                clearPreview();
                // 贴进来的是链接时，平台由链接说了算：类型下拉跟着切过去，
                // 免得「选了抖音却贴 B 站主页」还要人自己去改一遍。
                const detected = platformOfLink(v);
                if (detected && detected !== kind) setKind(detected);
              }}
            />
            {errs.url ? <em class="field-err">{errs.url}</em> : null}
          </label>
        </div>

        <div class="ext-grid">
          <div class="field">
            <span class="field-k">
              选取方式
              <span class="tip-i" tabIndex={0} aria-label="四种选取方式的说明">ⓘ
                <span class="tip-pop" role="tooltip">
                  最近 N 条：从最新一条往下数 N 条。指定日期：按发布时间取一段（含首尾两天）。
                  全部作品：把整个主页都取一遍。指定起点：从第 N 条开始，往更久或往最新接着取。
                </span>
              </span>
            </span>
            <Select
              ariaLabel="选取方式"
              value={mode}
              options={(['latest_n', 'date_range', 'all', 'from_start'] as SelectionMode[])
                .map((m) => ({ value: m, label: SELECTION[m] ?? m }))}
              onChange={(v) => { setMode(v as SelectionMode); setErrs({}); clearPreview(); }}
            />
          </div>

          {/* 动态字段：跟着选取方式走，不留一个用不上的输入框 */}
          {mode === 'latest_n' && (
            <div class={errs.n ? 'field field-n is-bad' : 'field field-n'}>
              <span>条数</span>
              <Stepper
                value={n}
                onChange={(v) => { setN(v); setErrs({}); clearPreview(); }}
                min={1}
                max={MAX_N}
                invalid={!!errs.n}
                ariaLabel="条数"
                describedBy="ext-n-err"
              />
              {errs.n ? <em class="field-err" id="ext-n-err">{errs.n}</em> : null}
            </div>
          )}
          {mode === 'from_start' && (
            <div class={errs.start ? 'field is-bad' : 'field'}>
              <span>起点序号</span>
              <div class="field-inline">
                <Stepper
                  value={startNo}
                  onChange={(v) => { setStartNo(v); setErrs({}); clearPreview(); }}
                  min={1}
                  max={MAX_START}
                  invalid={!!errs.start}
                  ariaLabel="起始序号"
                  describedBy="ext-start-err"
                />
                <Select
                  ariaLabel="提取方向"
                  value={direction}
                  options={[
                    { value: 'older', label: '往更久' },
                    { value: 'newer', label: '往最新' },
                  ]}
                  onChange={(v) => { setDirection(v as 'newer' | 'older'); clearPreview(); }}
                />
              </div>
              {errs.start ? <em class="field-err" id="ext-start-err">{errs.start}</em> : null}
            </div>
          )}
          {mode === 'date_range' && (
            <div class={errs.date ? 'field field-wide is-bad' : 'field field-wide'}>
              <span>发布日期的范围</span>
              <div class="date-range">
                <DateInput label="开始日期" placeholder="开始日期" value={since}
                           onInput={(v) => { setSince(v); setErrs({}); clearPreview(); }} />
                <span class="muted">至</span>
                <DateInput label="结束日期" placeholder="结束日期" value={until}
                           onInput={(v) => { setUntil(v); setErrs({}); clearPreview(); }} />
              </div>
              {errs.date ? <em class="field-err">{errs.date}</em> : null}
            </div>
          )}

          <div class="field">
            <span>存放方式</span>
            <Select
              ariaLabel="存放方式"
              value={storage}
              options={STORAGE_OPTS.map((s) => ({
                value: s, label: STORAGE[s]!, tip: STORAGE_DESC[s],
              }))}
              onChange={(v) => { setStorage(v as JobStorage); clearPreview(); }}
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
                // 两类格式没有交集：换类别时把格式收成那一类最常用的一个，不会留下无效组合
                setFormats([(FORMATS_OF[v] ?? ['source'])[0] as FormatKey]);
                clearPreview();
              }}
            />
          </div>
          <div class="field">
            <span>输出格式</span>
            <MultiSelect
              ariaLabel="输出格式"
              values={formats}
              options={(FORMATS_OF[mediaType] ?? ['source']).map((f) => ({
                value: f, label: FORMAT[f] ?? f, tip: FORMAT_TIP[f],
              }))}
              onChange={(v) => { setFormats(v as FormatKey[]); clearPreview(); }}
            />
          </div>
        </div>

        {/* 预览结果：一条淡蓝信息条，失败换成淡红；任何字段改动都会把它清掉 */}
        {preview ? (
          <div class="info-bar" role="status">
            <div class="info-main">
              <strong>预计选取 {preview.items.length} 条</strong>
              {preview.estimated_bytes > 0 ? <span>约 {fmtBytes(preview.estimated_bytes)}</span> : null}
              {preview.has_more ? <span>还有更多</span> : null}
              <span class="info-who">
                {preview.profile.name} · {platformName(preview.profile.platform)}
                {preview.profile.total_videos != null ? ` · 共 ${preview.profile.total_videos} 条` : ''}
                {' · 列表来源：'}
                {preview.cookie_source === 'configured' ? '已配置的授权' : preview.cookie_source === 'guest' ? '游客态' : '无'}
              </span>
            </div>
            {preview.items.length > 0 ? (
              <div class="info-list">
                {preview.items.slice(0, 8).map((v) => <span title={v.title}>{v.title}</span>)}
                {preview.items.length > 8 ? <span>……另有 {preview.items.length - 8} 条</span> : null}
              </div>
            ) : null}
          </div>
        ) : null}
        {previewErr ? <div class="info-bar bad" role="status">{previewErr}</div> : null}

        <div class="ext-foot">
          <label class="check">
            <input type="checkbox" checked={pinned} onChange={(e) => setPinned((e.target as HTMLInputElement).checked)} />
            <span>包含置顶作品</span>
          </label>
          <div class="ext-acts">
            <button type="button" class="btn btn-outline" disabled={busy !== ''} onClick={() => void doPreview()}>
              {busy === 'preview' ? '预览中…' : '预览'}
            </button>
            <button type="button" class="btn" disabled={busy !== ''} onClick={() => void doCreate()}>
              {busy === 'create' ? '正在开始…' : '开始提取'}
            </button>
          </div>
        </div>
      </div>

      {/* ---- 批量任务 ---- */}
      <div class="sec-head">
        <h2>批量任务</h2>
        <span class="sec-n">共 {visible.length} 条</span>
      </div>

      {/* 筛选栏：第一行状态分段 + 昵称 + 用户 ID，第二行四个下拉 + 发起时间 + 重置 */}
      <div class="filter-box cr-filters">
        <div class="filter-line">
          <Segmented
            ariaLabel="按状态筛选"
            value={fStatus}
            onChange={setFStatus}
            items={[
              { key: '', label: '全部', n: visible.length },
              ...(['pending', 'running', 'paused', 'done', 'failed', 'cancelled'] as const)
                .map((s) => ({ key: s, label: JOB[s] ?? s, n: statusCount(s) })),
            ]}
          />
          <input
            class="search"
            type="search"
            placeholder="输入昵称"
            aria-label="搜索昵称"
            value={fNick}
            onInput={(e) => setFNick((e.target as HTMLInputElement).value)}
          />
          <span class="ctrl-fixed f-uid">
            <input
              type="text"
              inputMode="numeric"
              placeholder="输入用户 ID"
              aria-label="搜索用户 ID"
              value={fUid}
              onInput={(e) => setFUid((e.target as HTMLInputElement).value)}
            />
          </span>
        </div>
        <div class="filter-line">
          <span class="ctrl-fixed">
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
          </span>
          <span class="ctrl-fixed">
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
          </span>
          <span class="ctrl-fixed">
            <Select
              ariaLabel="筛选：存放方式"
              value={fStorage}
              options={[{ value: '', label: '全部存放' }].concat(
                STORAGE_OPTS.map((s) => ({ value: s, label: STORAGE[s] ?? s })),
              )}
              onChange={setFStorage}
            />
          </span>
          <span class="ctrl-fixed">
            <Select
              ariaLabel="筛选：选取方式"
              value={fMode}
              options={[{ value: '', label: '全部方式' }].concat(
                (['latest_n', 'date_range', 'all', 'from_start'] as const)
                  .map((m) => ({ value: m, label: SELECTION[m] ?? m })),
              )}
              onChange={setFMode}
            />
          </span>
          <DateRange
            label="发起时间"
            from={fSince}
            to={fUntil}
            onChange={(f, t) => { setFSince(f); setFUntil(t); }}
          />
          {hasFilter ? (
            <button type="button" class="text-btn filter-reset" onClick={resetFilters}>重置筛选</button>
          ) : null}
        </div>
      </div>

      <section class="box">
        {jobsPhase === 'loading' ? <Loading what="批量任务" /> : null}
        {jobsPhase === 'error' ? <ErrorBox message={jobsErr} retry={() => void loadJobs()} /> : null}

        {jobsPhase === 'ok' && shownJobs.length === 0 ? (
          <div class="empty-state">
            <p>{jobs.length === 0 ? '还没有批量任务' : hasFilter ? '没有符合条件的任务' : '任务都被隐藏了'}</p>
            {jobs.length === 0 ? (
              <p class="small muted">上面填一个博主，先预览再建任务。</p>
            ) : hasFilter ? (
              <button type="button" class="btn small" onClick={resetFilters}>重置筛选</button>
            ) : (
              <button type="button" class="btn small" onClick={showAllJobs}>恢复显示</button>
            )}
          </div>
        ) : null}

        {shownJobs.length > 0 ? (
          <div class="table-wrap">
            <table class="table cr-table">
              {/* 九列全部写死宽度：博主改成固定宽，昵称超出就在格子里横向滚，
                  不再让它当弹性列把状态列顶到右边去 */}
              <colgroup>
                {COLS.map((c) => <col style={{ width: `var(--${c.cls})` }} />)}
              </colgroup>
              <thead>
                <tr>
                  {COLS.map((c) => (
                    <th class={[c.cls, c.mid ? 'mid' : ''].filter(Boolean).join(' ')} title={c.tip}>
                      {c.label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {shownJobs.map((j, idx) => {
                  const cid = creatorId(j);
                  const mediaLabel = `${j.media_type === 'audio' ? '音频' : '视频'} · `
                    + (j.formats?.length ? j.formats : [FORMATS_OF[j.media_type]?.[0] ?? 'source'])
                      .map((f) => FORMAT[f] ?? f).join(' / ');
                  return (
                    <Fragment key={j.id}>
                      <tr data-job={j.id} class={fresh === j.id ? 'job-fresh' : undefined}>
                        <td class="c-no muted">{idx + 1}</td>
                        <td class="c-who">
                          <div class="who-name" title={j.source_url}>{nickName(j)}</div>
                          <div class="who-sub">{platformName(j.platform)}{cid ? ` · ${cid}` : ''}</div>
                        </td>
                        <td>
                          <span class={`pill tone-${STATUS_TONE[j.status] ?? 'idle'}`} title={j.pause_reason ?? undefined}>
                            {JOB[j.status] ?? j.status}
                          </span>
                        </td>
                        <td class="c-sel" title={selectionText(j)}>{selectionShort(j)}</td>
                        <td class="c-out">
                          <div>{STORAGE[j.storage] ?? j.storage}</div>
                          <div class="sub">{mediaLabel}</div>
                        </td>
                        <td class="c-prog mid"><ProgCell job={j} /></td>
                        <td
                          class="c-bytes mid"
                          title={j.store_bytes > 0
                            ? `估算约 ${fmtBytes(j.estimated_bytes)}；服务器上已存 ${fmtBytes(j.store_bytes)}`
                            : '按作品平均码率估算，实际以服务器上的文件为准'}
                        >
                          {j.estimated_bytes > 0
                            ? <BytesCell n={j.estimated_bytes} />
                            : <span class="muted">—</span>}
                        </td>
                      <td class="c-at" title={`发起于 ${fmtDateTime(j.created_at)}`}>
                        <DateTimeCell at={j.created_at} />
                          <div class="sub">{relTime(j.created_at)}</div>
                        </td>
                        <td class="c-ops">{rowOps(j)}</td>
                      </tr>
                      {openId === j.id ? (
                        <tr class="job-sub">
                          <td colSpan={COLS.length}><JobItems jobId={j.id} /></td>
                        </tr>
                      ) : null}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : null}

        {shownJobs.length > 0 ? (
          <div class="box-foot">
            <span class="small muted">共 {shownJobs.length} 条</span>
            {hiddenCount > 0 ? (
              <span class="small muted">
                已隐藏 {hiddenCount} 个任务（只在这台设备的这个浏览器里）
                <button type="button" class="text-btn" onClick={showAllJobs}>恢复显示</button>
              </span>
            ) : null}
            {active ? <span class="foot-page small muted">有任务在跑，每 2.5 秒自动刷新</span> : null}
          </div>
        ) : null}
      </section>
    </>
  );
}

/** 进度：第一行「已抓 / 总数」，第二行按情况给细进度条或「已存服务器 N / M」。
 *
 *  斜杠两侧各留 4ch 固定宽度，位数变化时斜杠不会左右挪；整块在单元格里居中。
 *  进度条只在没跑完时出现（跑完/取消到底了就不必再画一条满格）。
 */
function ProgCell({ job }: { job: CreatorJob }) {
  const total = job.total || 0;
  const done = job.done || 0;
  const pct = total ? Math.min(100, Math.round((done / total) * 100)) : 0;
  const barTone = job.status === 'cancelled' ? 'idle' : job.status === 'failed' ? 'bad' : 'info';
  const tip = `已抓 ${done} / ${total || '?'}`
    + (job.failed > 0 ? `，${job.failed} 条失败` : '')
    + (job.pause_reason ? `；暂停原因：${job.pause_reason}` : '');
  return (
    <div class="prog" title={tip}>
      <span class="prog-n">
        <span class="prog-l">{done}</span>
        <span class="prog-s">/</span>
        <span class="prog-r">{total || '?'}</span>
      </span>
      {total > 0 && done < total ? (
        <span class={`prog-bar tone-${barTone}`}><i style={{ width: `${pct}%` }} /></span>
      ) : null}
      {/* 这里曾经给「服务器存储」的任务多显示一行「已存服务器 N / M」。已删：
          服务端把 media_path 和 download_state='done' 写在同一条 UPDATE 里
          （creator_jobs.py:1092），两个计数恒等 → 副行永远是主行的复制。 */}
      {job.failed > 0 ? <span class="prog-sub bad">{job.failed} 条失败</span> : null}
    </div>
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
        {items.map((i) => (
          <li class="item-row">
            {/* 置顶作品不占序号：序号列显示「置顶」标签；普通作品显示全局序号（后端算好，跨页稳定） */}
            <span class="item-no" aria-hidden="true">
              {i.is_pinned ? <span class="tag pin-tag">置顶</span> : (i.seq ?? '—')}
            </span>
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
          <span class="small muted pager-total">第 {page} / {pages} 页</span>
          <button
            type="button"
            class="btn btn-ghost small"
            disabled={page >= pages}
            onClick={() => setPage(page + 1)}
          >
            下一页
          </button>
        </nav>
      )}
    </div>
  );
}
