/** 5c：媒体库与播放。
 *
 * 这一版把"每行一串按钮"收掉了：**行内不再有按钮**，所有动作都从条目本身出发——
 *
 * - 单击一行 = 播放/收起这一条（播放条就在这一行里面）；
 * - 双击标题 = 就地改名；
 * - 勾选（可多选）之后，底部出现批量条：批量下载、删除。
 *
 * 这样一眼看过去只有"音频 / 视频"两个类型按钮和几个筛选器，列表本身是干净的。
 *
 * 数据仍全部走当前账号的 `/v2/tracks`；播放交给 `<audio>/<video>` 取 `/v2/media/{id}`
 * （服务端支持 Range，拖动进度条不会重下整段）；下载是同一个地址加 `?download=1`。
 */
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';
import { downloadUrl, mediaUrl, ownerApi, type MediaType, type Track } from '../api';
import { Chips, Empty, ErrorBox, fmtBytes, fmtDuration, fmtTime, Loading } from '../ui';

const STORAGE_LABEL: Record<Track['storage'], string> = {
  archive: '存储',
  cache: '缓存',
  metadata: '只记清单',
};

type SortKey = 'created_desc' | 'created_asc' | 'duration_desc' | 'duration_asc' | 'title' | 'bytes_desc';

const SORTS: { key: SortKey; label: string }[] = [
  { key: 'created_desc', label: '入库时间（新→旧）' },
  { key: 'created_asc', label: '入库时间（旧→新）' },
  { key: 'duration_desc', label: '时长（长→短）' },
  { key: 'duration_asc', label: '时长（短→长）' },
  { key: 'title', label: '标题（A→Z）' },
  { key: 'bytes_desc', label: '文件大小（大→小）' },
];

/** 双击判定窗口：这段时间内的单击先不播，等它过期才播。 */
const DBLCLICK_MS = 220;

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
  const [sort, setSort] = useState<SortKey>('created_desc');
  const [playing, setPlaying] = useState<number | null>(null);
  const [picked, setPicked] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState<number | null>(null);
  const [draft, setDraft] = useState('');
  const clickTimer = useRef<number | null>(null);
  /** 改名提交中：防止"回车保存"和"失焦保存"各发一次 PATCH。 */
  const committing = useRef(false);

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
    return () => {
      if (clickTimer.current) window.clearTimeout(clickTimer.current);
    };
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
    return rows.sort((a, b) => {
      switch (sort) {
        case 'created_asc': return a.created_at - b.created_at || a.id - b.id;
        case 'duration_desc': return (b.duration_sec ?? -1) - (a.duration_sec ?? -1);
        case 'duration_asc': return (a.duration_sec ?? Number.MAX_SAFE_INTEGER) - (b.duration_sec ?? Number.MAX_SAFE_INTEGER);
        case 'title': return (a.title || '').localeCompare(b.title || '', 'zh-Hans-CN');
        case 'bytes_desc': return (b.bytes || 0) - (a.bytes || 0);
        default: return b.created_at - a.created_at || b.id - a.id;
      }
    });
  }, [items, kind, q, platform, onlyFile, from, to, sort]);

  const pickedTracks = items.filter((t) => picked.includes(t.id));
  const allShownPicked = shown.length > 0 && shown.every((t) => picked.includes(t.id));

  function replace(next: Track) {
    setItems((prev) => prev.map((t) => (t.id === next.id ? next : t)));
  }

  /** 单击延迟一点点再播：双击改名时不要顺手把这首也放起来。 */
  function togglePlay(t: Track) {
    if (clickTimer.current) window.clearTimeout(clickTimer.current);
    clickTimer.current = window.setTimeout(() => {
      clickTimer.current = null;
      setPlaying((prev) => (prev === t.id ? null : t.id));
    }, DBLCLICK_MS);
  }

  function beginRename(t: Track) {
    if (clickTimer.current) {
      window.clearTimeout(clickTimer.current);
      clickTimer.current = null;
    }
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

  /** 删除：只有一次确认，不解释服务端怎么做——用户要确认的是"真的不要了"。 */
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
      <h1>媒体库与播放</h1>

      {phase === 'loading' && <Loading what="媒体库" />}
      {phase === 'error' && <ErrorBox message={err} retry={() => void load()} />}

      {phase === 'ok' && items.length === 0 && (
        <Empty>
          <strong>媒体库里还没有内容</strong>
          <p class="small muted">在「服务器提取」里提交一个链接，入库后就会出现在这里。</p>
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

          <div class="toolbar filters">
            <input
              class="search"
              type="search"
              placeholder="搜标题、UP 主、作品号"
              value={q}
              onInput={(e) => setQ((e.target as HTMLInputElement).value)}
              aria-label="搜索媒体库"
            />
            <select class="search" value={platform} onChange={(e) => setPlatform((e.target as HTMLSelectElement).value)}
                    aria-label="按平台筛选">
              <option value="all">全部平台</option>
              <option value="local">本机导入</option>
              <option value="bilibili">B 站</option>
              <option value="douyin">抖音</option>
            </select>
            <select class="search" value={sort} onChange={(e) => setSort((e.target as HTMLSelectElement).value as SortKey)}
                    aria-label="排序方式">
              {SORTS.map((s) => <option value={s.key}>{s.label}</option>)}
            </select>
            <label class="small muted">
              从 <input class="search date" type="date" value={from} aria-label="起始日期"
                       onInput={(e) => setFrom((e.target as HTMLInputElement).value)} />
            </label>
            <label class="small muted">
              到 <input class="search date" type="date" value={to} aria-label="结束日期"
                       onInput={(e) => setTo((e.target as HTMLInputElement).value)} />
            </label>
            <label class="small muted">
              <input type="checkbox" checked={onlyFile} aria-label="只看服务器有文件的"
                     onChange={(e) => setOnlyFile((e.target as HTMLInputElement).checked)} />
              只看有文件
            </label>
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
                    <div
                      class="track-main"
                      role="button"
                      tabIndex={0}
                      onClick={() => togglePlay(t)}
                      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') togglePlay(t); }}
                    >
                      <span class="track-kind" aria-hidden="true">{t.media_type === 'audio' ? '♪' : '▣'}</span>
                      <span class="track-body">
                        {editing === t.id ? (
                          <input
                            class="search"
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
                        <span class="small muted">
                          {t.uploader || '未知 UP 主'} · {fmtDuration(t.duration_sec)} · {t.platform} ·{' '}
                          {fmtTime(t.created_at)}
                        </span>
                      </span>
                      <span class="track-badges">
                        <span class="tag">{STORAGE_LABEL[t.storage]}</span>
                        {t.has_file
                          ? <span class="tag ok">有文件 {fmtBytes(t.bytes)}</span>
                          : <span class="tag">无文件</span>}
                      </span>
                    </div>
                  </div>

                  {/* 播放条在这一行内部：点哪一首就哪一首展开，再点一下收起。 */}
                  {playing === t.id && <Player track={t} />}
                </li>
              ))}
            </ul>
          )}
          {err && <div class="alert warn small">{err}</div>}
          <p class="small muted">
            单击一行播放，双击标题改名；勾选后可以批量下载或删除。列表最多取 10000 条，
            更早的条目请用搜索或时间范围定位。
          </p>
        </>
      )}
    </>
  );
}

/**
 * 条目内部的播放条：只有播放器本身。
 *
 * `key` 用条目 id：切到另一首时重建 media 元素，上一首的声音立刻停。
 */
function Player({ track }: { track: Track }) {
  const src = mediaUrl(track.id);
  return (
    <div class="player-inline">
      {track.media_type === 'video' ? (
        <video key={track.id} class="player-media" controls preload="metadata" src={src} />
      ) : (
        <audio key={track.id} class="player-media" controls preload="metadata" src={src} />
      )}
    </div>
  );
}
