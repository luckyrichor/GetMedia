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
import { Chips, DateInput, Empty, ErrorBox, fmtBytes, fmtDuration, fmtTime, Loading } from '../ui';

const STORAGE_LABEL: Record<Track['storage'], string> = {
  archive: '存储',
  cache: '缓存',
  metadata: '元数据',
};

type SortField = 'created' | 'duration' | 'title' | 'bytes';
type SortDir = 'asc' | 'desc';

const SORT_FIELDS: {
  key: SortField;
  label: string;
  /** 选中这个字段时用哪个方向（不是记住上一次的方向：换字段意味着换意图） */
  dir: SortDir;
  dirLabel: Record<SortDir, string>;
}[] = [
  { key: 'created', label: '时间', dir: 'desc', dirLabel: { desc: '新→旧', asc: '旧→新' } },
  { key: 'duration', label: '时长', dir: 'desc', dirLabel: { desc: '长→短', asc: '短→长' } },
  { key: 'title', label: '标题', dir: 'asc', dirLabel: { desc: 'Z→A', asc: 'A→Z' } },
  { key: 'bytes', label: '文件大小', dir: 'desc', dirLabel: { desc: '大→小', asc: '小→大' } },
];

const PLATFORMS = [
  { value: 'all', label: '全部平台' },
  { value: 'local', label: '导入' },
  { value: 'bilibili', label: 'B 站' },
  { value: 'douyin', label: '抖音' },
];

/** 上传上限：nginx 给 owner 通道的 body 上限是 210 MiB，留一点余量按 200 MiB 挡。
 *  真正的配额（全站 archive 池）在服务端，超了会回 409，错误文案原样显示。 */
const UP_LIMIT = 200 * 1024 ** 2;
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

/** 行里那行小字显示平台，别把 `local` 这种内部值直接摊给用户看。 */
function platformLabel(p: string): string {
  return PLATFORMS.find((x) => x.value === p)?.label ?? p;
}

export function LibraryPage() {
  const [items, setItems] = useState<Track[]>([]);
  const [phase, setPhase] = useState<'loading' | 'ok' | 'error'>('loading');
  const [err, setErr] = useState('');
  const [kind, setKind] = useState<MediaType>('audio');
  const [q, setQ] = useState('');
  const [platform, setPlatform] = useState('all');
  const [onlyFile, setOnlyFile] = useState(false);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [sortField, setSortField] = useState<SortField>('created');
  const [sortDir, setSortDir] = useState<SortDir>('desc');
  const [playing, setPlaying] = useState<number | null>(null);
  /** 展开着的那个播放器是否处于暂停：跟着媒体元素自己的 play/pause 事件走。 */
  const [paused, setPaused] = useState(false);
  const [picked, setPicked] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
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
      if (f.size > UP_LIMIT) {
        failed.push(`${f.name}：${fmtBytes(f.size)}，超过 ${fmtBytes(UP_LIMIT)} 上限`);
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

  async function load() {
    setPhase('loading');
    try {
      setItems(await ownerApi.tracks());
      setPhase('ok');
    } catch (e) {
      setErr(messageFor(e));
      setPhase('error');
    }
  }

  useEffect(() => {
    void load();
  }, []);

  const audioN = items.filter((t) => t.media_type === 'audio').length;
  const videoN = items.length - audioN;

  const shown = useMemo(() => {
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

  const pickedTracks = items.filter((t) => picked.includes(t.id));
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

  /** 批量下载：逐个点一个隐藏的 `<a download>`，间隔一点，免得浏览器把连发当弹窗拦掉。 */
  async function downloadPicked() {
    const list = pickedTracks.filter((t) => t.has_file || t.source_url);
    if (list.length === 0) return;
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
    } finally {
      setBusy(false);
    }
  }

  /** 删除整条：只有一次确认，不解释服务端到底怎么删。 */
  async function removeTrack(t: Track) {
    if (!confirm(`确定删除《${t.title || '无标题'}》吗？`)) return;
    setBusy(true);
    try {
      await ownerApi.deleteTrack(t.id);
      setItems((prev) => prev.filter((x) => x.id !== t.id));
      setPicked((prev) => prev.filter((id) => id !== t.id));
      setPlaying((prev) => (prev === t.id ? null : prev));
      setErr('');
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function removePicked() {
    const list = pickedTracks;
    if (list.length === 0) return;
    if (!confirm(`确定删除选中的 ${list.length} 项吗？`)) return;
    setBusy(true);
    const gone: number[] = [];
    try {
      for (const t of list) {
        await ownerApi.deleteTrack(t.id);
        gone.push(t.id);
      }
      setErr('');
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      const removed = new Set(gone);
      setItems((prev) => prev.filter((t) => !removed.has(t.id)));
      setPicked((prev) => prev.filter((id) => !removed.has(id)));
      if (playing != null && removed.has(playing)) setPlaying(null);
      setBusy(false);
    }
  }

  return (
    <>
      <h1>媒体库</h1>

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
          可多选；单个不超过 {fmtBytes(UP_LIMIT)}。原件存进服务器「存储」，计入账号配额。
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

          <div class="filters">
            <div class="filter-row">
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
            <div class="filter-row dates">
              <label class="small muted date-field">
                从
                <DateInput class="search date" label="起始日期" value={from} onInput={setFrom} />
              </label>
              <label class="small muted date-field">
                到
                <DateInput class="search date" label="结束日期" value={to} onInput={setTo} />
              </label>
              <label class="small muted check-field">
                <input type="checkbox" checked={onlyFile} aria-label="只看服务器有文件的"
                       onChange={(e) => setOnlyFile((e.target as HTMLInputElement).checked)} />
                只看有文件
              </label>
            </div>
          </div>

          <div class="toolbar selectbar">
            <label class="small muted">
              <input type="checkbox" checked={allShownPicked} aria-label="全选当前列表"
                     onChange={(e) => pickAll((e.target as HTMLInputElement).checked)} />
              全选
            </label>
            <span class="small muted">本页 {shown.length} 项</span>
            {picked.length > 0 && (
              <>
                <span class="small">已选 {picked.length} 项</span>
                <button type="button" class="btn small" disabled={busy}
                        onClick={() => void downloadPicked()}>
                  批量下载
                </button>
                <button type="button" class="btn btn-ghost small" disabled={busy}
                        onClick={() => void removePicked()}>
                  删除
                </button>
                <button type="button" class="btn btn-ghost small" onClick={() => setPicked([])}>
                  取消
                </button>
              </>
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
                    <span class="track-no">{index + 1}</span>
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
                            <span class="track-title" title="双击改名"
                                  onDblClick={(e) => { e.stopPropagation(); beginRename(t); }}>
                              {t.title || '(无标题)'}
                            </span>
                          )}
                          <span class="track-author" title={t.uploader || '未知 UP 主'}>
                            {t.uploader || '未知 UP 主'}
                          </span>
                        </span>
                        {/* 顺序固定：平台 → 时长 → 入库时间 → 存放方式 → 文件大小（无文件）。
                            每格各一种颜色，相邻三格颜色必不相同，扫一眼就能分开。 */}
                        <span class="track-meta">
                          <span class="meta-tag t-plat">{platformLabel(t.platform)}</span>
                          <span class="meta-tag t-dur">{fmtDuration(t.duration_sec)}</span>
                          <span class="meta-tag t-time">{fmtTime(t.created_at)}</span>
                          <span class="meta-tag t-store">{STORAGE_LABEL[t.storage]}</span>
                          {t.has_file
                            ? <span class="meta-tag t-size">{fmtBytes(t.bytes)}</span>
                            : <span class="meta-tag t-none">无文件</span>}
                        </span>
                      </span>
                    </div>
                    <div class="track-actions">
                      <button type="button" class="btn btn-ghost small" onClick={() => playNow(t)}>
                        {playing === t.id && !paused ? '暂停' : '播放'}
                      </button>
                      {t.has_file || t.source_url ? (
                        <a class="btn btn-ghost small" href={downloadUrl(t.id)} download>下载</a>
                      ) : (
                        <button type="button" class="btn btn-ghost small" disabled
                                title="这条没有来源链接，服务器也取不到流，无法下载">
                          下载
                        </button>
                      )}
                      <button type="button" class="btn btn-ghost small" disabled={busy}
                              onClick={() => void removeTrack(t)}>
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
          {err && <div class="alert warn small">{err}</div>}
        </>
      )}
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
        title="点击切换正序 / 倒序"
        onClick={onFlip}
      >
        <span class="sort-name">{cur.label}</span>
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

  const common = {
    class: 'player-media',
    controls: true,
    preload: 'metadata' as const,
    src: mediaUrl(track.id),
    onPlay: () => onState(true),
    onPause: () => onState(false),
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
    </div>
  );
}
