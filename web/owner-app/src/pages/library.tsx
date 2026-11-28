/** 5c：媒体库。
 *
 * 行内保留三个入口：**播放 / 暂停、下载、删除**（都在每一行右侧）。
 *
 * - 「删除」**只有一个**：`local` 条目删文件 + 写墓碑，只有清单的条目只写墓碑，
 *   这是服务端按平台分叉的实现细节，界面上不再分成两个按钮。
 * - **只有「播放」按钮能播**：点条目的标题/信息区不会播（那里只看不点，双击标题是改名），
 *   播放条就展开在这一行里面，点「播放」直接出声、再点变「暂停」；
 * - 双击标题 = 就地改名（不再单独放「改名」按钮）；
 * - 勾选（可多选）之后，上方出现批量条：批量下载、删除。
 *
 * 排序是一个拆开的控件：左边是"按什么排"（点一下就正序/倒序互换），右边箭头才展开
 * 四位候选（时间、时长、标题、文件大小）——换字段时方向回到该字段的默认方向。
 *
 * 下拉用 `../select` 的自绘下拉而不是原生 `<select>`：macOS/Windows 的原生弹出层会盖住
 * 控件本身，点开就看不见"现在选的是哪个"。
 *
 * 数据仍全部走当前账号的 `/v2/tracks`；播放交给 `<audio>/<video>` 取 `/v2/media/{id}`
 * （服务端支持 Range，拖动进度条不会重下整段）；下载是同一个地址加 `?download=1`。
 */
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';
import { downloadUrl, mediaUrl, ownerApi, type MediaType, type Track } from '../api';
import { Select } from '../select';
import { KEYS, loadOnce, peek } from '../swr';
import { Chips, ConfirmDialog, DateInput, Empty, ErrorBox, fmtBytes, fmtDuration, fmtTime, Loading, PageHead, Pager, useToast } from '../ui';

/** 每页条数：默认 5 条，用户可在 5 / 10 / 20 之间自选。
 *  5 条在 1080p 下正好不用滚动就能看全；要快速扫一大段就切 20 条。
 *  选过的值记在 localStorage 里——刷新一次就跳回 5 条，等于这个设置白给了。 */
const PAGE_SIZES = [5, 10, 20] as const;
const PAGE_SIZE_DEFAULT = PAGE_SIZES[0];
const PAGE_SIZE_KEY = 'getmedia.library.pageSize';

function readPageSize(): number {
  try {
    const n = Number(localStorage.getItem(PAGE_SIZE_KEY));
    return (PAGE_SIZES as readonly number[]).includes(n) ? n : PAGE_SIZE_DEFAULT;
  } catch {
    // 隐私模式下 localStorage 会抛，读不到就用默认值
    return PAGE_SIZE_DEFAULT;
  }
}

function savePageSize(n: number) {
  try {
    localStorage.setItem(PAGE_SIZE_KEY, String(n));
  } catch {
    /* 存不了就算了，不影响本次使用 */
  }
}

type SortField = 'created' | 'duration' | 'title' | 'bytes';
type SortDir = 'asc' | 'desc';

const SORT_FIELDS: {
  key: SortField;
  label: string;
  /** 选中这个字段时用哪个方向（不是记住上一次的方向：换字段意味着换意图） */
  dir: SortDir;
  dirLabel: Record<SortDir, string>;
}[] = [
  { key: 'created', label: '时间', dir: 'desc', dirLabel: { desc: '最新在前', asc: '最早在前' } },
  { key: 'duration', label: '时长', dir: 'desc', dirLabel: { desc: '最长在前', asc: '最短在前' } },
  { key: 'title', label: '标题', dir: 'asc', dirLabel: { desc: 'Z→A', asc: 'A→Z' } },
  { key: 'bytes', label: '大小', dir: 'desc', dirLabel: { desc: '最大在前', asc: '最小在前' } },
];

const PLATFORMS = [
  { value: 'all', label: '全部平台' },
  { value: 'local', label: '导入' },
  { value: 'bilibili', label: 'B 站' },
  { value: 'douyin', label: '抖音' },
];

/** 服务器给 owner 通道的请求体上限是 1024 MiB（nginx `client_max_body_size 1024m`）。
 *  以前这里按 200 MiB 挡，是因为更早的 nginx 只有 210m——0.1.44 提到 1024m 之后这道
 *  限制就没必要了，网页端跟着手机端一起放到 1 GB。
 *  真正的配额（全站 archive 池，5 GiB）在服务端，超了会回 409，错误文案原样显示。 */
const UP_LIMIT = 1024 * 1024 ** 2;
/** 文件本身要比请求体上限小一点：multipart 还有边界与表单头（几百字节）。 */
const FILE_LIMIT = UP_LIMIT - 1024 * 1024;
/** 「单个不超过 X」里的 X：整数不带小数位，1 GiB 写「1 GB」而不是「1.0 GB」。
 *  只在这个门槛文案上用；列表里的实际体积仍走 `fmtBytes`（保留一位小数，
 *  这样 1.2 GB 和 1.8 GB 才分得开）。 */
const limitText = (n: number): string => fmtBytes(n).replace(/\.0 /, ' ');
/** 浏览器给的 MIME 有时是空的（尤其 mkv/flv），退回看扩展名。 */
const VIDEO_EXT = /\.(mp4|m4v|mov|webm|mkv|avi|flv|wmv|ts|mpg|mpeg)$/i;

function mediaTypeOf(f: File): MediaType {
  if (f.type.startsWith('video/')) return 'video';
  if (f.type.startsWith('audio/')) return 'audio';
  return VIDEO_EXT.test(f.name) ? 'video' : 'audio';
}

/** 条目键：`local` 条目没有平台作品号，用时间戳 + 随机串凑一个，避免和历史墓碑撞键。 */
function localId(): string {
  return `local-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

/** 文件状态那一格：把「存放方式」和「有没有文件」并成一句人话。
 *
 *  服务端 `storage` 有三种（`server/getmedia/media.py` 顶部）：
 *  - `archive`：文件长期存在 media_dir，**计入 owner 配额** → 「已存储 3.9 MB」
 *  - `cache`：文件在 cache_dir，**到期会被清掉** → 「已缓存 3.9 MB」（不能也叫"存储"）
 *  - `metadata`：只有一行记录，媒体始终由客户端当场解析 → 「仅元数据」
 *  有没有文件看的是 `has_file`（= `media_path IS NOT NULL`），不是 `storage` 本身，
 *  所以这里以 `has_file` 为准，`storage` 只决定用「已存储」还是「已缓存」。 */
function fileState(t: Track): string {
  if (!t.has_file) return '仅元数据';
  return `${t.storage === 'cache' ? '已缓存' : '已存储'} ${fmtBytes(t.bytes)}`;
}

/** 行里那行小字显示平台，别把 `local` 这种内部值直接摊给用户看。 */
function platformLabel(p: string): string {
  return PLATFORMS.find((x) => x.value === p)?.label ?? p;
}

/** 能不能播/下载。
 *
 *  「仅元数据」不是"没有媒体"，只是"服务器没存文件"：`GET /v2/media/{id}` 会在
 *  没有落盘文件时当场解析并中转（见 `server/getmedia/media_api.py`），所以只要有
 *  `source_url` 就能放。真正取不到的是连来源链接都没有的条目（比如已脱钩的旧行）。 */
function canStream(t: Track): boolean {
  return t.has_file || !!t.source_url;
}

/** 播放/下载按钮的悬停说明：为什么能放、为什么要等、为什么不能放。 */
function streamHint(t: Track): string | undefined {
  if (t.has_file) return undefined;
  if (t.source_url) return '服务器只存了元数据，播放或下载时会现解析，可能需要几秒';
  return '这条既没有服务器文件也没有来源链接，无法播放或下载';
}

export function LibraryPage() {
  /** 上次拉到的列表：有就直接拿来当初始值，切回这一页立刻有内容，不用再等一个来回。 */
  const cached = peek<Track[]>(KEYS.tracks);
  const [items, setItems] = useState<Track[]>(cached ?? []);
  const [phase, setPhase] = useState<'loading' | 'ok' | 'error'>(cached ? 'ok' : 'loading');
  const [err, setErr] = useState('');
  const [kind, setKind] = useState<MediaType>('audio');
  const [q, setQ] = useState('');
  const [platform, setPlatform] = useState('all');
  const [onlyFile, setOnlyFile] = useState(false);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [sortField, setSortField] = useState<SortField>('created');
  const [sortDir, setSortDir] = useState<SortDir>('desc');
  /** 第几页（1 起）。筛选/排序一变就回第 1 页，否则会停在一个已经不存在的页码上。 */
  const [page, setPage] = useState(1);
  /** 每页条数：初值取上次选过的（读不到就是 5）。 */
  const [pageSize, setPageSize] = useState(readPageSize);
  const [playing, setPlaying] = useState<number | null>(null);
  /** 展开着的那个播放器是否处于暂停：跟着媒体元素自己的 play/pause 事件走。 */
  const [paused, setPaused] = useState(false);
  const [picked, setPicked] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
  /** 待确认的删除：单条记 id，批量记 id 数组。null = 没弹窗。
   *  用页面内的 `ConfirmDialog` 而不是原生 `confirm`——后者会带出「<IP> 显示：」那串前缀。 */
  const [confirming, setConfirming] = useState<
    { kind: 'one'; track: Track } | { kind: 'many'; ids: number[] } | null
  >(null);
  const toast = useToast();
  const [editing, setEditing] = useState<number | null>(null);
  const [draft, setDraft] = useState('');
  /** 挂在当前展开的播放器上：暂停/继续要直接操作媒体元素，不能靠重渲染。 */
  const mediaRef = useRef<HTMLMediaElement | null>(null);
  /** 改名提交中：防止"回车保存"和"失焦保存"各发一次 PATCH。 */
  const committing = useRef(false);
  /** 「上传本地文件」：隐藏的文件选择框，按钮点它。 */
  const fileRef = useRef<HTMLInputElement | null>(null);
  /** 串行上传的进度（第几个/共几个/当前文件名）；null 表示没在传。 */
  const [up, setUp] = useState<{ i: number; n: number; name: string } | null>(null);
  const [upNote, setUpNote] = useState('');
  const [upErr, setUpErr] = useState<string[]>([]);

  /** 逐个文件串行：失败不中断，最后汇总「成功 x，失败 y」并列出原因。 */
  async function uploadMany(list: File[]) {
    if (!list.length) return;
    setUpErr([]);
    setUpNote('');
    setBusy(true);
    const failed: string[] = [];
    let ok = 0;
    for (let i = 0; i < list.length; i++) {
      const f = list[i]!;
      setUp({ i: i + 1, n: list.length, name: f.name });
      if (f.size > FILE_LIMIT) {
        failed.push(`${f.name}：${fmtBytes(f.size)}，超过单个 ${limitText(UP_LIMIT)} 的上限`);
        continue;
      }
      try {
        const r = await ownerApi.createTrack({
          media_type: mediaTypeOf(f),
          platform: 'local',
          video_id: localId(),
          title: f.name.replace(/\.[^.]+$/, '').slice(0, 200) || f.name,
        });
        await ownerApi.uploadTrackFile(r.track.id, f);
        ok++;
      } catch (e) {
        failed.push(`${f.name}：${messageFor(e)}`);
      }
    }
    setUp(null);
    setBusy(false);
    setUpErr(failed);
    setUpNote(`上传完成：成功 ${ok} 个，失败 ${failed.length} 个。`);
    if (ok > 0) await load();
  }

  /** `quiet` = 不切到「加载中」：批量删除后重新对齐服务端状态时用，
   *  否则整张列表会闪一下、勾选和滚动位置也跟着跳。 */
  async function load(quiet = false) {
    if (!quiet) setPhase('loading');
    try {
      // 走 `loadOnce`：预取已经在拉的话直接复用那一个请求，不重复发
      setItems(await loadOnce(KEYS.tracks, () => ownerApi.tracks()));
      setPhase('ok');
    } catch (e) {
      setErr(messageFor(e));
      if (!quiet) setPhase('error');
    }
  }

  useEffect(() => {
    // 有缓存时静默刷新：先让用户看见列表，再在背后对齐服务端
    void load(!!cached);
  }, []);

  /** 筛选/排序/标签页一变就清空选中，并且回到第 1 页。
   *
   *  列表是**纯前端过滤**，所以「选中了但看不见」没有任何正当场景；而批量删除不可逆，
   *  宁可让用户重勾一次，也不能让他在「已选 4 项 / 本页 0 项」的状态下按删除。
   *  翻页同样清空——批量动作只作用于当前这一页看得见的条目。
   *  `prev.length ? [] : prev` 是为了空选中时不触发一次多余的重渲染。 */
  useEffect(() => {
    setPage(1);
  }, [kind, q, platform, onlyFile, from, to, sortField, sortDir, pageSize]);

  useEffect(() => {
    setPicked((prev) => (prev.length ? [] : prev));
  }, [kind, q, platform, onlyFile, from, to, sortField, sortDir, page, pageSize]);

  const audioN = items.filter((t) => t.media_type === 'audio').length;
  const videoN = items.length - audioN;

  /** 全部命中的条目（未分页）。计数、空状态都看它。 */
  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    const startMs = from ? new Date(`${from}T00:00:00`).getTime() : null;
    const endMs = to ? new Date(`${to}T23:59:59`).getTime() : null;
    const rows = items.filter((t) => {
      if (t.media_type !== kind) return false;
      if (platform !== 'all' && t.platform !== platform) return false;
      if (onlyFile && !t.has_file) return false;
      const ms = t.created_at * 1000;
      if (startMs != null && ms < startMs) return false;
      if (endMs != null && ms > endMs) return false;
      if (!needle) return true;
      return `${t.title} ${t.uploader ?? ''} ${t.video_id}`.toLowerCase().includes(needle);
    });
    const dir = sortDir === 'asc' ? 1 : -1;
    return rows.sort((a, b) => {
      switch (sortField) {
        case 'duration':
          return cmpNum(a.duration_sec, b.duration_sec, dir);
        case 'bytes':
          // 没有文件的条目没有"大小"可比，一律沉底，不跟着方向翻上来
          return cmpNum(a.has_file ? a.bytes : null, b.has_file ? b.bytes : null, dir);
        case 'title':
          return dir * (a.title || '').localeCompare(b.title || '', 'zh-Hans-CN') || (a.id - b.id);
        default:
          return dir * (a.created_at - b.created_at) || (a.id - b.id);
      }
    });
  }, [items, kind, q, platform, onlyFile, from, to, sortField, sortDir]);

  const pages = Math.max(1, Math.ceil(filtered.length / pageSize));
  /** 删到只剩一页时页码可能越界，渲染与翻页都按夹住后的页码来，免得出现空页 */
  const pageSafe = Math.min(page, pages);
  /** 当前页真正渲染出来的条目 = 用户能勾选的那一批 */
  const shown = useMemo(
    () => filtered.slice((pageSafe - 1) * pageSize, pageSafe * pageSize),
    [filtered, pageSafe, pageSize],
  );

  /** 批量动作只认「当前列表里看得见 + 勾上了」的交集：`picked` 里即便混进了不可见的 id，
   *  也不会被批量下载/删除碰到。计数也用它，避免「已选 N 项」比列表里的勾还多。 */
  const pickedTracks = shown.filter((t) => picked.includes(t.id));
  const allShownPicked = shown.length > 0 && shown.every((t) => picked.includes(t.id));

  function replace(next: Track) {
    setItems((prev) => prev.map((t) => (t.id === next.id ? next : t)));
  }

  /** 行内「播放」按钮是唯一的播放入口（点条目的文字区不再触发）；
   *  同一条再点一下就是暂停 / 继续。 */
  function playNow(t: Track) {
    if (playing === t.id) {
      // 按钮文字和这里用同一个状态源（`paused` 跟着媒体元素的 play/pause 事件走），
      // 写着"暂停"就一定是暂停，不会出现按钮和实际播放状态各说各话。
      if (paused) playElement(mediaRef.current, () => setPaused(true));
      else mediaRef.current?.pause();
      return;
    }
    setPaused(false);
    setPlaying(t.id);
  }

  /** 收起这一条的播放条：先停声再卸载，按钮回到「播放」，下次点从头开始。 */
  function closePlayer() {
    mediaRef.current?.pause();
    setPlaying(null);
    setPaused(false);
  }

  function beginRename(t: Track) {
    setEditing(t.id);
    setDraft(t.title);
  }

  async function commitRename(t: Track) {
    if (committing.current) return;
    const title = draft.trim();
    if (!title || title === t.title) {
      setEditing(null);
      return;
    }
    committing.current = true;
    setBusy(true);
    try {
      replace(await ownerApi.renameTrack(t.id, title));
      setEditing(null);
      setErr('');
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      committing.current = false;
      setBusy(false);
    }
  }

  function pick(id: number, on: boolean) {
    setPicked((prev) => (on ? [...new Set([...prev, id])] : prev.filter((x) => x !== id)));
  }

  function pickAll(on: boolean) {
    const ids = shown.map((t) => t.id);
    setPicked((prev) => (on ? [...new Set([...prev, ...ids])] : prev.filter((x) => !ids.includes(x))));
  }

  /** 批量下载：逐个点一个隐藏的 `<a download>`，间隔一点，免得浏览器把连发当弹窗拦掉。
   *
   *  「仅元数据」的条目也**能**下载：`GET /v2/media/{id}` 在服务器没有文件时会当场解析并中转
   *  （见 `server/getmedia/media_api.py` 的 `/v2/media/{id}`）。真正取不到的是连 `source_url`
   *  都没有的条目，这类要明确告诉用户跳过了几条，而不是静默少下几个文件。 */
  async function downloadPicked() {
    const list = pickedTracks.filter((t) => t.has_file || t.source_url);
    const skipped = pickedTracks.length - list.length;
    if (list.length === 0) {
      toast.show(`没有可下载的条目：${skipped} 项既没有文件也没有来源链接`, 'warn');
      return;
    }
    setBusy(true);
    try {
      for (const [i, t] of list.entries()) {
        if (i > 0) await new Promise((r) => setTimeout(r, 250));
        const a = document.createElement('a');
        a.href = downloadUrl(t.id);
        a.download = '';
        a.style.display = 'none';
        document.body.appendChild(a);
        a.click();
        a.remove();
      }
      setErr('');
      // 批量动作收尾：清掉勾选，别让用户对着"已选 N 项"再点一次
      setPicked([]);
      toast.show(
        skipped > 0 ? `已下载 ${list.length} 项，跳过 ${skipped} 项（无来源链接）` : `已开始下载 ${list.length} 项`,
        skipped > 0 ? 'warn' : 'ok',
      );
    } finally {
      setBusy(false);
    }
  }

  /** 单条删除：只弹一次确认，弹窗里写明要删哪一条。 */
  function askRemove(t: Track) {
    setConfirming({ kind: 'one', track: t });
  }

  /** 批量删除：确认框里写数量 + 前三个标题，其余用「等 N 项」概括。 */
  function askRemovePicked() {
    if (pickedTracks.length === 0) return;
    setConfirming({ kind: 'many', ids: pickedTracks.map((t) => t.id) });
  }

  /** 真正执行删除。单条与批量共用一段：失败到一半也把已删的从列表里摘掉，
   *  然后统一清勾选、向服务端对齐一次。 */
  async function doRemove() {
    const c = confirming;
    if (!c) return;
    const ids = c.kind === 'one' ? [c.track.id] : c.ids;
    setBusy(true);
    const gone: number[] = [];
    try {
      for (const id of ids) {
        await ownerApi.deleteTrack(id);
        gone.push(id);
      }
      setErr('');
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      const removed = new Set(gone);
      setItems((prev) => prev.filter((t) => !removed.has(t.id)));
      // 单条删除只把这一条从选中里摘掉（用户可能还勾着别的）；
      // 批量删除成功也好、删到一半失败也好，勾选一律清空——剩下的半截选中没有意义。
      setPicked((prev) => (c.kind === 'one' ? prev.filter((id) => !removed.has(id)) : []));
      if (playing != null && removed.has(playing)) setPlaying(null);
      setConfirming(null);
      setBusy(false);
      void load(true);
    }
  }

  return (
    <>
      <PageHead title="媒体库" />

      {/* 上传本地文件：建一条 platform=local 的条目再传原件，和手机端"本机导入"是同一条路径 */}
      <div class="toolbar">
        <button type="button" class="btn" disabled={busy || up !== null} onClick={() => fileRef.current?.click()}>
          上传本地文件
        </button>
        <input
          ref={fileRef}
          type="file"
          accept="audio/*,video/*"
          multiple
          hidden
          aria-label="选择要上传的音频或视频"
          onChange={(e) => {
            const el = e.currentTarget;
            const pickedFiles = Array.from(el.files ?? []);
            el.value = ''; // 清空，同一个文件再选一次也能触发 change
            void uploadMany(pickedFiles);
          }}
        />
        <span class="small muted">
          可多选；单个不超过 {limitText(UP_LIMIT)}。原件存进服务器「存储」，计入账号配额。
          <strong>双击标题可改名。</strong>
        </span>
      </div>

      {up && (
        <div class="alert info small">
          正在上传 第 {up.i}/{up.n} 个：<span class="mono">{up.name}</span>
        </div>
      )}
      {upNote && <div class="alert info small">{upNote}</div>}
      {upErr.length > 0 && (
        <div class="alert warn small">
          <strong>有 {upErr.length} 个没上传成功：</strong>
          <ul class="plain">
            {upErr.map((t) => <li>{t}</li>)}
          </ul>
        </div>
      )}

      {phase === 'loading' && <Loading what="媒体库" />}
      {phase === 'error' && <ErrorBox message={err} retry={() => void load()} />}

      {phase === 'ok' && items.length === 0 && (
        <Empty>
          <strong>媒体库里还没有内容</strong>
          <p class="small muted">上传本地文件，或在「单个提取」里提交一个链接，入库后就会出现在这里。</p>
        </Empty>
      )}

      {phase === 'ok' && items.length > 0 && (
        <>
          <div class="toolbar">
            <Chips
              items={[
                { key: 'audio', label: '音频', n: audioN },
                { key: 'video', label: '视频', n: videoN },
              ]}
              value={kind}
              onChange={(k) => setKind(k as MediaType)}
              ariaLabel="按类型筛选"
            />
            <span class="small muted">共 {items.length} 条</span>
          </div>

          {/* 筛选区一行放完：搜索 / 平台 / 起止日期 / 只看有文件 / 排序。
              窄屏靠 flex-wrap 自己换行，不再人为拆成两行（两行各空一半才是真浪费）。 */}
          <div class="filters">
            <input
              class="search"
              type="search"
              placeholder="搜标题、UP 主、作品号"
              value={q}
              onInput={(e) => setQ((e.target as HTMLInputElement).value)}
              aria-label="搜索媒体库"
            />
            <div class="filter-select">
              <Select value={platform} options={PLATFORMS} onChange={setPlatform} ariaLabel="按平台筛选" />
            </div>
            <span class="date-range-inline">
              <DateInput class="search date" label="起始日期" placeholder="开始" value={from} onInput={setFrom} />
              <span class="sep" aria-hidden="true">至</span>
              <DateInput class="search date" label="结束日期" placeholder="结束" value={to} onInput={setTo} />
            </span>
            <label class="small muted check-field">
              <input type="checkbox" checked={onlyFile} aria-label="只看服务器有文件的"
                     onChange={(e) => setOnlyFile((e.target as HTMLInputElement).checked)} />
              只看有文件
            </label>
            <SortSplit
              field={sortField}
              dir={sortDir}
              onField={(f) => {
                setSortField(f);
                setSortDir(SORT_FIELDS.find((x) => x.key === f)!.dir);
              }}
              onFlip={() => setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))}
            />
          </div>

          {/* 选择栏常驻、高度固定：勾选后内容换成批量动作，但**不改变高度**，
              所以下面的列表不会上下弹。 */}
          <div class={pickedTracks.length > 0 ? 'selectbar on' : 'selectbar'}>
            {pickedTracks.length > 0 ? (
              <div class="batch-act">
                <span class="small"><strong>已选 {pickedTracks.length} 项</strong></span>
                <button type="button" class="btn small" disabled={busy}
                        onClick={() => void downloadPicked()}>
                  批量下载
                </button>
                <button type="button" class="btn btn-danger small" disabled={busy}
                        onClick={askRemovePicked}>
                  批量删除
                </button>
                <button type="button" class="btn btn-ghost small" onClick={() => setPicked([])}>
                  取消选择
                </button>
              </div>
            ) : (
              <div class="batch-act">
                <label class="small muted">
                  <input type="checkbox" checked={allShownPicked} aria-label="全选当前列表"
                         onChange={(e) => pickAll((e.target as HTMLInputElement).checked)} />
                  全选
                </label>
                <span class="small muted">
                  本页 {shown.length} 项{filtered.length > shown.length ? ` / 共 ${filtered.length} 条` : ''}
                </span>
              </div>
            )}
          </div>

          {shown.length === 0 ? (
            <Empty>
              <strong>没有匹配的条目</strong>
              <p class="small muted">换个关键词，或放宽时间范围。</p>
            </Empty>
          ) : (
            <ul class="tracks">
              {shown.map((t, index) => (
                <li class={playing === t.id ? 'track on' : 'track'}>
                  <div class="track-row">
                    <label class="track-pick">
                      <input type="checkbox" checked={picked.includes(t.id)}
                             aria-label={`选择 ${t.title || '无标题'}`}
                             onChange={(e) => pick(t.id, (e.target as HTMLInputElement).checked)} />
                    </label>
                    {/* 序号跨页连续：第 2 页第一条是 6，不是 1 */}
                    <span class="track-no">{(pageSafe - 1) * pageSize + index + 1}</span>
                    <div class="track-main">
                      <span class="track-kind" aria-hidden="true">{t.media_type === 'audio' ? '♪' : '▣'}</span>
                      <span class="track-body">
                        {/* 标题与作者同一行：作者长短不一时，元信息行仍然对齐，
                            不会因为有的作者名字长、有的短而让整列参差不齐。 */}
                        <span class="track-head">
                          {editing === t.id ? (
                            <input
                              class="search track-rename"
                              value={draft}
                              maxLength={200}
                              aria-label="新标题"
                              autoFocus
                              onInput={(e) => setDraft((e.target as HTMLInputElement).value)}
                              onClick={(e) => e.stopPropagation()}
                              onBlur={() => void commitRename(t)}
                              onKeyDown={(e) => {
                                if (e.key === 'Enter') void commitRename(t);
                                if (e.key === 'Escape') setEditing(null);
                              }}
                            />
                          ) : (
                            /* `title` 给完整标题（长标题两行截断，得能悬停看全）；
                               「双击改名」放进 aria-label —— 两个都塞 title 会互相顶掉。 */
                            <span
                              class="track-title"
                              title={t.title || '(无标题)'}
                              aria-label={`${t.title || '(无标题)'}（双击改名）`}
                              onDblClick={(e) => { e.stopPropagation(); beginRename(t); }}
                            >
                              {t.title || '(无标题)'}
                            </span>
                          )}
                          <span class={t.uploader ? 'track-author' : 'track-author unknown'}
                                title={t.uploader || '未知 UP 主'}>
                            {t.uploader || '未知 UP 主'}
                          </span>
                        </span>
                        {/* 顺序固定：平台 → 时长 → 入库时间 → 文件状态。
                            「存放方式」和「有没有文件」并成一格（见 `fileState`）。 */}
                        <span class="track-meta">
                          <span class="meta-tag t-plat">{platformLabel(t.platform)}</span>
                          <span class="meta-tag t-dur">{fmtDuration(t.duration_sec)}</span>
                          <span class="meta-tag t-time">{fmtTime(t.created_at)}</span>
                          <span class={t.has_file ? 'meta-tag t-file' : 'meta-tag t-file none'}>
                            {fileState(t)}
                          </span>
                        </span>
                      </span>
                    </div>
                    <div class="track-actions">
                      <button
                        type="button"
                        class="btn btn-ghost small"
                        disabled={!canStream(t) || busy}
                        title={streamHint(t)}
                        onClick={() => playNow(t)}
                      >
                        {playing === t.id && !paused ? '暂停' : '播放'}
                      </button>
                      {canStream(t) ? (
                        <a class="btn btn-ghost small" href={downloadUrl(t.id)} download
                           title={streamHint(t)}>下载</a>
                      ) : (
                        <button type="button" class="btn btn-ghost small" disabled
                                title={streamHint(t)}>
                          下载
                        </button>
                      )}
                      {/* 删除是危险动作：红字红边，跟上面两个蓝色按钮分开 */}
                      <button type="button" class="btn btn-danger small" disabled={busy}
                              onClick={() => askRemove(t)}>
                        删除
                      </button>
                    </div>
                  </div>

                  {/* 播放条在这一行内部：点哪一首就哪一首展开，条上「收起」把它收回去。 */}
                  {playing === t.id && (
                    <Player track={t} mediaRef={mediaRef}
                            onState={(on) => setPaused(!on)}
                            onClose={closePlayer} />
                  )}
                </li>
              ))}
            </ul>
          )}

          <Pager
            page={pageSafe}
            pages={pages}
            onPage={setPage}
            total={filtered.length}
            label="媒体库分页"
            pageSize={pageSize}
            sizeOptions={PAGE_SIZES}
            onPageSize={(n) => {
              savePageSize(n);
              setPageSize(n);
            }}
          />
          {err && <div class="alert warn small">{err}</div>}
        </>
      )}

      {/* 删除确认：页面内弹窗，替掉会带出「<IP> 显示：」前缀的原生 confirm。
          默认焦点落在「取消」上（`ConfirmDialog` 里取消按钮排第一个）。 */}
      {confirming?.kind === 'one' && (
        <ConfirmDialog
          title="删除这一条？"
          danger
          busy={busy}
          confirmText="删除"
          onCancel={() => setConfirming(null)}
          onConfirm={() => void doRemove()}
        >
          <p>确定删除《{confirming.track.title || '无标题'}》吗？删除后无法恢复。</p>
        </ConfirmDialog>
      )}
      {confirming?.kind === 'many' && (
        <ConfirmDialog
          title={`删除选中的 ${confirming.ids.length} 项？`}
          danger
          busy={busy}
          confirmText={`删除 ${confirming.ids.length} 项`}
          onCancel={() => setConfirming(null)}
          onConfirm={() => void doRemove()}
        >
          <p>以下条目将被删除，删除后无法恢复：</p>
          <ul class="confirm-list">
            {shown
              .filter((t) => confirming.ids.includes(t.id))
              .slice(0, 3)
              .map((t) => <li key={t.id}>{t.title || '(无标题)'}</li>)}
          </ul>
          {confirming.ids.length > 3 && (
            <p class="small muted">等 {confirming.ids.length} 项</p>
          )}
        </ConfirmDialog>
      )}

      {toast.node}
    </>
  );
}

/** 数字比较：`null` 一侧永远沉底，不跟着正/倒序翻到最前面。 */
function cmpNum(a: number | null, b: number | null, dir: number): number {
  if (a == null && b == null) return 0;
  if (a == null) return 1;
  if (b == null) return -1;
  return (a - b) * dir;
}

/** 播放（jsdom 里 `play()` 没有实现、返回 undefined，不能直接 `.catch`）。
 *
 * `onFail` 用于"浏览器不放行自动播放"这种失败：让按钮退回「播放」，
 * 而不是继续显示「暂停」却一点声音都没有。
 */
function playElement(el: HTMLMediaElement | null, onFail?: () => void) {
  if (!el) return;
  const p = el.play?.();
  if (p && typeof p.catch === 'function') p.catch(() => onFail?.());
}

/**
 * 排序控件：左边"按什么排 + 方向"，点一下翻方向；右边箭头才展开字段列表。
 *
 * 拆成两半是因为两件事完全不同：换字段是低频动作，翻方向是高频动作，
 * 塞进同一个下拉就得每次点开再点一次选项。
 */
function SortSplit({ field, dir, onField, onFlip }: {
  field: SortField;
  dir: SortDir;
  onField: (f: SortField) => void;
  onFlip: () => void;
}) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const cur = SORT_FIELDS.find((x) => x.key === field)!;
  const dirText = cur.dirLabel[dir];

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (root.current && !root.current.contains(e.target as Node)) setOpen(false);
    };
    const onScroll = () => setOpen(false);
    document.addEventListener('mousedown', onDown);
    window.addEventListener('scroll', onScroll, true);
    return () => {
      document.removeEventListener('mousedown', onDown);
      window.removeEventListener('scroll', onScroll, true);
    };
  }, [open]);

  return (
    <div class={open ? 'sort-split on' : 'sort-split'} ref={root}>
      <button
        type="button"
        class="sort-field"
        aria-label={`排序依据：${cur.label}（${dirText}），点击切换正倒序`}
        title={`当前：${dirText}，点击切换`}
        onClick={onFlip}
      >
        <span class="sort-name">{cur.label}</span>
        {/* 箭头旁边补一句人话：光看 ↑↓ 得先想一下是"最新在前"还是"最早在前" */}
        <span class="sort-dir">{dirText}</span>
        <span class="sort-arrow" aria-hidden="true">{dir === 'asc' ? '↑' : '↓'}</span>
      </button>
      <button
        type="button"
        class="sort-caret"
        aria-label="选择排序依据"
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        <span aria-hidden="true">▾</span>
      </button>
      {open && (
        <ul class="select-menu sort-menu" role="listbox" aria-label="排序依据">
          {SORT_FIELDS.map((f) => (
            <li
              key={f.key}
              role="option"
              aria-selected={f.key === field}
              class={f.key === field ? 'select-opt on' : 'select-opt'}
              onClick={() => {
                onField(f.key);
                setOpen(false);
              }}
            >
              <span class="select-label">{f.label}</span>
              {/* 当前这个字段跟着实际方向走，其余字段显示"选中后会用的默认方向" */}
              <span class="select-hint">{f.dirLabel[f.key === field ? dir : f.dir]}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/**
 * 条目内部的播放条。
 *
 * 展开即播放：原来是"先出现一个没在播的播放器，再点它自己的播放键"，
 * 等于每次都要点两下。这里挂载后直接 `play()`；用户随后在里面暂停/继续，
 * 通过 `onState` 把真实状态告诉外面，外面那个按钮的文字才不会对不上。
 *
 * `key` 用条目 id：切到另一首时重建 media 元素，上一首的声音立刻停。
 */
function Player({ track, mediaRef, onState, onClose }: {
  track: Track;
  mediaRef: { current: HTMLMediaElement | null };
  onState: (playing: boolean) => void;
  onClose: () => void;
}) {
  /** 自己这一份引用：换到上面那条时，旧播放器的卸载清理可能晚于新播放器挂载，
   *  共享引用会被清空，所以播哪个元素必须以自己的为准。 */
  const own = useRef<HTMLMediaElement | null>(null);
  /** 「仅元数据」要现解析，先给一句"在解析"，别让人对着一个不动的播放器发呆；
   *  解析失败（平台拒流）时也得说清是取不到，而不是"加载中"永远转。 */
  const [phase, setPhase] = useState<'loading' | 'ok' | 'failed'>(track.has_file ? 'ok' : 'loading');
  /** 失败原因取自服务端的 `message`（如「抖音直链需要授权 Cookie…」）：
   *  `<audio>` 的 error 事件读不到响应体，只能先探一次。 */
  const [failMsg, setFailMsg] = useState('');

  const attach = (el: HTMLVideoElement | HTMLAudioElement | null) => {
    own.current = el;
    mediaRef.current = el;
  };

  useEffect(() => {
    const el = own.current;
    playElement(el, () => onState(false));
    return () => {
      // 只有共享引用还指着"我"的时候才清：否则会把刚挂上的新播放器抹掉
      if (mediaRef.current === el) mediaRef.current = null;
    };
  }, [track.id]);

  /** 先探一个字节：能播就让媒体元素去取，不能播就把服务端那句话原样告诉用户。
   *  只有「仅元数据」才探——有文件的条目本来就是 200/206，多一个请求没意义。 */
  useEffect(() => {
    if (track.has_file) return;
    let cancelled = false;
    (async () => {
      try {
        const r = await fetch(mediaUrl(track.id), {
          headers: { Range: 'bytes=0-0' },
          credentials: 'same-origin',
        });
        if (cancelled) return;
        if (r.ok) return;
        let msg = '';
        try {
          const body = await r.json() as { message?: string };
          msg = body?.message || '';
        } catch { /* 不是 JSON 就用状态码兜底 */ }
        setFailMsg(msg || `服务器返回 ${r.status}`);
        setPhase('failed');
        onState(false);
      } catch {
        // 网络层失败交给媒体元素自己报错，这里不抢
      }
    })();
    return () => { cancelled = true; };
  }, [track.id, track.has_file]);

  const common = {
    class: 'player-media',
    controls: true,
    preload: 'metadata' as const,
    src: mediaUrl(track.id),
    onPlay: () => { setPhase('ok'); onState(true); },
    onPlaying: () => setPhase('ok'),
    onPause: () => onState(false),
    onError: () => { setPhase('failed'); onState(false); },
  };

  return (
    <div class="player-inline">
      <div class="player-head">
        <span class="player-now" title={track.title}>
          {track.media_type === 'video' ? '正在播放视频' : '正在播放'}：{track.title}
        </span>
        <button type="button" class="btn btn-ghost small" onClick={onClose}>收起</button>
      </div>
      {track.media_type === 'video'
        ? <video key={track.id} ref={attach} {...common} />
        : <audio key={track.id} ref={attach} {...common} />}
      {phase === 'loading' && (
        <p class="player-note">
          这条只存了元数据，服务器正在现解析，可能需要几秒…
        </p>
      )}
      {phase === 'failed' && (
        <p class="player-note bad">
          {failMsg ? `取不到媒体流：${failMsg}` : '取不到媒体流：平台可能拒绝了这次解析。稍后重试，或在手机端重新解析这条。'}
        </p>
      )}
    </div>
  );
}
