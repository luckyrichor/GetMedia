/** 5c：媒体库与播放。
 *
 * 只读/改标题/删文件/删记录四件事，全部走当前登录账号的 `/v2/tracks`（不跨账号）。
 * 播放交给浏览器的 `<audio>/<video>` 直接取 `/v2/media/{id}`：服务端支持 Range，拖动进度条不会重下整段；
 * 没有落盘文件时服务端会当场解析并中转（不写盘），所以"仅元数据"的条目也能放。
 * 「下载」是同一个地址加 `?download=1`，服务端回 `Content-Disposition: attachment`，
 * 浏览器存到打开这个页面的那台电脑上（不需要 App、不经过手机）。
 */
import { useEffect, useMemo, useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';
import { downloadUrl, mediaUrl, ownerApi, type MediaType, type Track } from '../api';
import { Chips, Empty, ErrorBox, fmtBytes, fmtDuration, fmtTime, Loading } from '../ui';

type Filter = 'all' | MediaType;

const STORAGE_LABEL: Record<Track['storage'], string> = {
  archive: '归档',
  cache: '缓存',
  metadata: '仅元数据',
};

export function LibraryPage() {
  const [items, setItems] = useState<Track[]>([]);
  const [phase, setPhase] = useState<'loading' | 'ok' | 'error'>('loading');
  const [err, setErr] = useState('');
  const [filter, setFilter] = useState<Filter>('all');
  const [q, setQ] = useState('');
  const [selected, setSelected] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState<number | null>(null);
  const [draft, setDraft] = useState('');

  async function load() {
    setPhase('loading');
    try {
      const rows = await ownerApi.tracks();
      rows.sort((a, b) => b.created_at - a.created_at || b.id - a.id);
      setItems(rows);
      setPhase('ok');
    } catch (e) {
      setErr(messageFor(e));
      setPhase('error');
    }
  }

  useEffect(() => {
    void load();
  }, []);

  const shown = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return items.filter((t) => {
      if (filter !== 'all' && t.media_type !== filter) return false;
      if (!needle) return true;
      return `${t.title} ${t.uploader ?? ''} ${t.video_id}`.toLowerCase().includes(needle);
    });
  }, [items, filter, q]);

  const audioN = items.filter((t) => t.media_type === 'audio').length;
  const totalBytes = items.reduce((n, t) => n + (t.bytes || 0), 0);
  const current = items.find((t) => t.id === selected) ?? null;

  function replace(next: Track) {
    setItems((prev) => prev.map((t) => (t.id === next.id ? next : t)));
  }

  async function rename(t: Track) {
    const title = draft.trim();
    if (!title || title === t.title) {
      setEditing(null);
      return;
    }
    setBusy(true);
    try {
      replace(await ownerApi.renameTrack(t.id, title));
      setEditing(null);
      setErr('');
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function dropFile(t: Track) {
    if (!confirm(`删除《${t.title}》的服务器文件？记录会保留，之后播放需要重新解析。`)) return;
    setBusy(true);
    try {
      replace(await ownerApi.dropFile(t.id));
      setErr('');
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function removeTrack(t: Track) {
    if (!confirm(`从媒体库删除《${t.title}》？这是逻辑删除，会同步到手机端。`)) return;
    setBusy(true);
    try {
      await ownerApi.deleteTrack(t.id);
      setItems((prev) => prev.filter((x) => x.id !== t.id));
      if (selected === t.id) setSelected(null);
      setErr('');
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <h1>媒体库与播放</h1>
      <p class="lead">
        服务器上属于当前账号的条目。共 {items.length} 条（音频 {audioN}、视频 {items.length - audioN}），
        落盘占用 {fmtBytes(totalBytes)}。播放直接读服务器，拖动进度条只取需要的那一段。
      </p>

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
                { key: 'all', label: '全部', n: items.length },
                { key: 'audio', label: '音频', n: audioN },
                { key: 'video', label: '视频', n: items.length - audioN },
              ]}
              value={filter}
              onChange={(k) => setFilter(k as Filter)}
              ariaLabel="按类型筛选"
            />
            <input
              class="search"
              type="search"
              placeholder="搜标题、UP 主、作品号"
              value={q}
              onInput={(e) => setQ((e.target as HTMLInputElement).value)}
              aria-label="搜索媒体库"
            />
          </div>

          {current && <Player track={current} onClose={() => setSelected(null)} />}

          {shown.length === 0 ? (
            <Empty>
              <strong>没有匹配的条目</strong>
              <p class="small muted">换个关键词，或把类型切回「全部」。</p>
            </Empty>
          ) : (
            <ul class="tracks">
              {shown.map((t) => (
                <li class={selected === t.id ? 'track on' : 'track'}>
                  <button type="button" class="track-main" onClick={() => setSelected(t.id)}>
                    <span class="track-kind" aria-hidden="true">{t.media_type === 'audio' ? '♪' : '▣'}</span>
                    <span class="track-body">
                      <span class="track-title">{t.title || '(无标题)'}</span>
                      <span class="small muted">
                        {t.uploader || '未知 UP 主'} · {fmtDuration(t.duration_sec)} · {t.platform} ·{' '}
                        {fmtTime(t.created_at)}
                      </span>
                    </span>
                    <span class="track-badges">
                      <span class="tag">{STORAGE_LABEL[t.storage]}</span>
                      {t.has_file ? <span class="tag ok">有文件 {fmtBytes(t.bytes)}</span> : <span class="tag">无文件</span>}
                    </span>
                  </button>

                  {editing === t.id ? (
                    <div class="row-actions">
                      <input
                        class="search"
                        value={draft}
                        maxLength={200}
                        aria-label="新标题"
                        onInput={(e) => setDraft((e.target as HTMLInputElement).value)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') void rename(t);
                          if (e.key === 'Escape') setEditing(null);
                        }}
                      />
                      <button type="button" class="btn small" disabled={busy} onClick={() => void rename(t)}>保存</button>
                      <button type="button" class="btn btn-ghost small" onClick={() => setEditing(null)}>取消</button>
                    </div>
                  ) : (
                    <div class="row-actions">
                      <button type="button" class="btn btn-ghost small" onClick={() => setSelected(t.id)}>播放</button>
                      {t.has_file || t.source_url ? (
                        <a class="btn btn-ghost small" href={downloadUrl(t.id)} download>
                          下载
                        </a>
                      ) : (
                        <button type="button" class="btn btn-ghost small" disabled
                                title="这条没有来源链接，服务器也取不到流，无法下载">
                          下载
                        </button>
                      )}
                      <button
                        type="button"
                        class="btn btn-ghost small"
                        onClick={() => { setEditing(t.id); setDraft(t.title); }}
                      >
                        改名
                      </button>
                      <button type="button" class="btn btn-ghost small" disabled={busy || !t.has_file}
                              onClick={() => void dropFile(t)}>
                        删文件
                      </button>
                      <button type="button" class="btn btn-ghost small" disabled={busy}
                              onClick={() => void removeTrack(t)}>
                        删除记录
                      </button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}
          {err && <div class="alert warn small">{err}</div>}
          <p class="small muted">
            列表按入库时间倒序，最多取 10000 条（服务端游标翻页）。更早的条目请用搜索定位，
            或到「库维护」看占用情况。
          </p>
        </>
      )}
    </>
  );
}

function Player({ track, onClose }: { track: Track; onClose: () => void }) {
  const src = mediaUrl(track.id);
  return (
    <div class="player">
      <div class="player-head">
        <strong>{track.title || '(无标题)'}</strong>
        {track.has_file || track.source_url ? (
          <a class="btn btn-ghost small" href={downloadUrl(track.id)} download>下载到本机</a>
        ) : null}
        <button type="button" class="btn btn-ghost small" onClick={onClose}>关闭</button>
      </div>
      {track.media_type === 'video' ? (
        <video key={track.id} class="player-media" controls preload="metadata" src={src} />
      ) : (
        <audio key={track.id} class="player-media" controls preload="metadata" src={src} />
      )}
      <p class="small muted">
        {track.has_file
          ? '正在读服务器上的文件，支持拖动进度条（Range）。'
          : '服务器上没有文件，播放时当场解析并中转，不落盘；网络断开即停。'}
      </p>
    </div>
  );
}
