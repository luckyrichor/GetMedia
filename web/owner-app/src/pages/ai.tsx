/** 5f：AI 视频重建。
 *
 * 服务端默认关闭（`GETMEDIA_AI_ENABLED=false`）。关闭时这一页只读：能看已有项目与产物，
 * 但新建/分析/生成都被禁用，并明确写出"未配置模型"——不给一个点了才报错的按钮。
 *
 * 生成流程由 worker 执行（`getmedia-ai-worker`），页面只入队与看进度；产物是服务端文件，
 * 用 <video> 直接取（支持 Range），"标记已保存"只是改状态，不删产物。
 */
import { useEffect, useRef, useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';
import {
  ownerApi, recreationSourceUrl, recreationVideoUrl,
  type AiCapabilities, type Recreation, type RecreationRun, type Track,
} from '../api';
import { Select } from '../select';
import { fmtBytes, fmtTime, Loading, ErrorBox, Empty, relTime } from '../ui';

const RUN: Record<string, string> = {
  queued: '排队中', running: '生成中', done: '已完成', failed: '失败', cancelled: '已取消',
};
const KIND: Record<string, string> = { analyze: '分析画面', generate: '生成视频' };
const REC: Record<string, string> = {
  draft: '草稿', queued: '排队中', running: '进行中', done: '已完成', failed: '失败',
  saved: '已保存', expired: '文件已过期',
};

export function AiPage() {
  const [caps, setCaps] = useState<AiCapabilities | null>(null);
  const [projects, setProjects] = useState<Recreation[]>([]);
  const [tracks, setTracks] = useState<Track[]>([]);
  const [sel, setSel] = useState<Recreation | null>(null);
  const [phase, setPhase] = useState<'loading' | 'ok' | 'error'>('loading');
  const [err, setErr] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [desc, setDesc] = useState('');
  const [trackId, setTrackId] = useState('');
  const fileRef = useRef<HTMLInputElement | null>(null);
  const timer = useRef<number | null>(null);

  async function load() {
    setPhase('loading');
    setErr('');
    try {
      const [c, list] = await Promise.all([ownerApi.aiCapabilities(), ownerApi.recreations()]);
      setCaps(c);
      setProjects(list);
      setPhase('ok');
    } catch (e) {
      setErr(messageFor(e));
      setPhase('error');
    }
  }

  useEffect(() => {
    void load();
    ownerApi.tracks().then(
      (rows) => setTracks(rows.filter((t) => t.media_type === 'video' && t.has_file)),
      () => setTracks([]),
    );
    return () => {
      if (timer.current) window.clearInterval(timer.current);
    };
  }, []);

  async function open(id: number) {
    setErr('');
    try {
      const p = await ownerApi.recreation(id);
      setSel(p);
      setDesc(p.description ?? '');
    } catch (e) {
      setErr(messageFor(e));
    }
  }

  const active = Boolean(sel?.runs?.some((r) => r.status === 'queued' || r.status === 'running'));
  useEffect(() => {
    if (!active || !sel) {
      if (timer.current) {
        window.clearInterval(timer.current);
        timer.current = null;
      }
      return;
    }
    if (timer.current) return;
    timer.current = window.setInterval(() => void open(sel.id), 2500);
    return () => {
      if (timer.current) {
        window.clearInterval(timer.current);
        timer.current = null;
      }
    };
  }, [active, sel?.id]);

  async function afterChange(id: number) {
    await open(id);
    setProjects(await ownerApi.recreations());
  }

  async function upload(f: File | null) {
    if (!f) return;
    setBusy(true);
    setErr('');
    setNote('');
    try {
      const p = await ownerApi.uploadRecreation(f);
      setNote(`已创建项目 #${p.id}。下一步：先分析或直接写描述，再生成。`);
      await afterChange(p.id);
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      if (fileRef.current) fileRef.current.value = '';
      setBusy(false);
    }
  }

  async function fromTrack() {
    const id = Number(trackId);
    if (!id) {
      setErr('先选一个媒体库里的视频');
      return;
    }
    setBusy(true);
    setErr('');
    try {
      const p = await ownerApi.recreationFromTrack(id);
      setNote(`已用媒体库 #${id} 建项目 #${p.id}。`);
      await afterChange(p.id);
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function saveDesc() {
    if (!sel) return;
    setBusy(true);
    setErr('');
    try {
      await ownerApi.setDescription(sel.id, desc);
      setNote('描述已保存。');
      await afterChange(sel.id);
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function run(kind: 'analyze' | 'generate') {
    if (!sel) return;
    setBusy(true);
    setErr('');
    try {
      await (kind === 'analyze' ? ownerApi.analyze(sel.id) : ownerApi.generate(sel.id));
      setNote(kind === 'analyze' ? '已加入分析队列。' : '已加入生成队列。');
      await afterChange(sel.id);
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function cancelRun(r: RecreationRun) {
    if (!sel) return;
    setBusy(true);
    try {
      await ownerApi.cancelRun(sel.id, r.id);
      await afterChange(sel.id);
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function saveOutput(r: RecreationRun) {
    if (!sel) return;
    setBusy(true);
    try {
      const out = await ownerApi.saveOutput(sel.id, r.id);
      setNote(`已标记保存（${fmtBytes(out.bytes)}），产物仍留在服务器上，到期后统一清理。`);
      await afterChange(sel.id);
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  if (phase === 'loading') return <Loading what="AI 重建" />;
  if (phase === 'error') return <ErrorBox message={err} retry={() => void load()} />;

  const off = !caps?.available;
  // 只有 generate 的产物是视频；analyze 的结果不是能播的东西，不放进播放器。
  const doneRun = sel?.runs?.find((r) => r.status === 'done' && r.kind === 'generate');
  const st = caps?.storage;
  const pct = st && st.quota > 0 ? Math.min(100, Math.round((st.used / st.quota) * 100)) : 0;
  const room = st ? Math.max(0, st.quota - st.used) : 0;
  const days = caps?.retention_days ?? 14;

  return (
    <>
      <h1>AI 视频重建</h1>
      <p class="lead">
        把一段视频当作素材，让模型先分析画面、再按你的描述生成新视频。执行在服务器的
        <code>getmedia-ai-worker</code> 上，网页只负责入队与看进度。
      </p>

      {off && (
        <div class="alert warn">
          <strong>AI 重建当前不可用。</strong> {caps?.reason || '服务端未配置模型'}
          （开关是 <code>GETMEDIA_AI_ENABLED</code>，模型凭据在 <code>llm.env</code>）。
          这一页只读：已有项目与产物还能看，新建与生成都禁用。
        </div>
      )}

      {note && <div class="alert info small">{note}</div>}
      {err && <div class="alert warn small">{err}</div>}

      <h2>新建项目</h2>
      <div class="panel-card">
        {st && st.quota > 0 && (
          <div class="metric">
            <div class="metric-k">服务器库（AI 素材与产物计入这里，和媒体库共用 3 GiB）</div>
            <div class="metric-v">
              {fmtBytes(st.used)} <span class="small muted">/ {fmtBytes(st.quota)}</span>
            </div>
            <div class="bar" role="img" aria-label={`${pct}%`}><i style={`width:${pct}%`} /></div>
            <div class="small muted">
              {pct}% 已用 · 其中 AI {fmtBytes(st.ai_bytes)}（{st.ai_files} 个文件）·
              还可上传 {fmtBytes(room)} · 保留 {days} 天
            </div>
          </div>
        )}
        <div class="field-row">
          <div class="field">
            <span>用媒体库里的视频（仅"服务器上有文件"的视频）</span>
            <Select
              ariaLabel="用媒体库里的视频"
              value={trackId}
              placeholder={tracks.length === 0 ? '没有可用的视频' : '选择一个视频'}
              disabled={off || busy || tracks.length === 0}
              options={[
                { value: '', label: tracks.length === 0 ? '没有可用的视频' : '选择一个视频' },
                ...tracks.map((t) => ({ value: String(t.id), label: `#${t.id} ${t.title}` })),
              ]}
              onChange={setTrackId}
            />
          </div>
          <button type="button" class="btn" disabled={off || busy || !trackId} onClick={() => void fromTrack()}>
            用这个视频建项目
          </button>
        </div>

        <div class="field-row">
          <label class="field">
            <span>或上传一个文件{caps ? `（不超过 ${fmtBytes(caps.max_upload_bytes)}）` : ''}</span>
            <input ref={fileRef} type="file" accept="video/*" disabled={off || busy}
                   onChange={(e) => void upload((e.target as HTMLInputElement).files?.[0] ?? null)} />
          </label>
        </div>
        <p class="small muted">
          上传的文件先落到服务器的项目目录（600 权限），再交给 worker。源文件与产物保留 {days} 天
          （<code>GETMEDIA_AI_RETENTION_DAYS</code>）：到期由服务端删掉文件、保留记录，
          项目状态变"文件已过期"；有排队/执行中任务的会等它跑完再清。空间一直不够时先清缓存或旧项目。
        </p>
      </div>

      <h2>项目</h2>
      {projects.length === 0 ? (
        <Empty>
          <strong>还没有重建项目</strong>
          <p class="small muted">{off ? '服务端开通 AI 后才能新建。' : '上面选一个视频或传一个文件开始。'}</p>
        </Empty>
      ) : (
        <ul class="jobs">
          {projects.map((p) => (
            <li class={sel?.id === p.id ? 'job on' : 'job'}>
              <div class="job-head">
                <span class={`tag ${p.status === 'saved' ? 'ok' : p.status === 'failed' ? 'bad' : ''}`}>
                  {REC[p.status] ?? p.status}
                </span>
                <span class="grow ellipsis" title={p.title}>{p.title}</span>
                <span class="small muted nowrap">{relTime(p.updated_at)}</span>
              </div>
              <div class="small muted">
                #{p.id}
                {p.source_track_id ? ` · 来自媒体库 #${p.source_track_id}` : ' · 上传文件'}
                {' · '}{p.description ? `已有描述（${p.description.length} 字）` : '还没有描述'}
                {' · '}
                {p.status === 'expired' || !p.source_path ? (
                  <span class="tag warn-tag">文件已过期清理</span>
                ) : (
                  `还剩 ${p.days_left ?? days} 天`
                )}
              </div>
              <div class="row-actions">
                <button type="button" class="btn btn-ghost small" onClick={() => void open(p.id)}>
                  {sel?.id === p.id ? '刷新' : '打开'}
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}

      {sel && (
        <div class="panel-card">
          <div class="job-head">
            <strong>#{sel.id} {sel.title}</strong>
            <span class="tag">{REC[sel.status] ?? sel.status}</span>
            <span class="grow" />
            <button type="button" class="btn btn-ghost small" onClick={() => setSel(null)}>收起</button>
          </div>
          <p class="small muted">
            {sel.status === 'expired' || !sel.source_path
              ? `文件已过期清理（保留期 ${sel.retention_days ?? days} 天），记录保留。`
              : `保留期 ${sel.retention_days ?? days} 天，还剩 ${sel.days_left ?? days} 天；`
                + '到期自动清理文件、保留记录，有排队/执行中任务时等它跑完。'}
          </p>

          <h3>源视频</h3>
          {sel.source_path ? (
            <video class="player-media" controls preload="metadata" src={recreationSourceUrl(sel.id)} />
          ) : (
            <p class="small muted">
              该项目的文件已过期清理（保留期 {sel.retention_days ?? days} 天），记录还在但源视频不在服务器上了。
              要重新做，上传一个新文件建项目即可。
            </p>
          )}

          <h3>描述</h3>
          <label class="field">
            <span>想要什么样的结果（生成前必填；也可以先"分析画面"让模型给一版）</span>
            <textarea rows={4} maxLength={4000} value={desc} disabled={off || busy}
                      onInput={(e) => setDesc((e.target as HTMLTextAreaElement).value)} />
          </label>
          <div class="row-actions">
            <button type="button" class="btn small" disabled={off || busy} onClick={() => void saveDesc()}>保存描述</button>
            <button type="button" class="btn btn-ghost small" disabled={off || busy} onClick={() => void run('analyze')}>
              分析画面
            </button>
            <button type="button" class="btn btn-ghost small"
                    disabled={off || busy || !desc.trim()} onClick={() => void run('generate')}>
              生成视频
            </button>
          </div>
          {off && <p class="small muted">服务端未开通，按钮禁用；这不是页面故障。</p>}

          <h3>任务</h3>
          {(sel.runs ?? []).length === 0 ? (
            <p class="small muted">还没有任务。</p>
          ) : (
            <ul class="plain tight">
              {(sel.runs ?? []).map((r) => (
                <li class="item-row">
                  <span class={`tag ${r.status === 'done' ? 'ok' : r.status === 'failed' ? 'bad' : r.status === 'running' ? 'warn' : ''}`}>
                    {RUN[r.status] ?? r.status}
                  </span>
                  <span class="grow">
                    {KIND[r.kind] ?? r.kind} #{r.id}
                    {typeof r.progress === 'number' && r.status === 'running' ? ` · ${r.progress}%` : ''}
                    <span class="small muted">
                      {r.created_at ? ` · 建于 ${fmtTime(r.created_at)}` : ''}
                      {r.error_code ? ` · ${r.error_code}` : ''}
                    </span>
                  </span>
                  {(r.status === 'queued' || r.status === 'running') && (
                    <button type="button" class="btn btn-ghost small" disabled={busy} onClick={() => void cancelRun(r)}>
                      取消
                    </button>
                  )}
                  {r.status === 'done' && (
                    <button type="button" class="btn btn-ghost small" disabled={busy} onClick={() => void saveOutput(r)}>
                      标记已保存
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}

          {doneRun ? (
            <>
              <h3>产物</h3>
              <video class="player-media" controls preload="metadata"
                     src={recreationVideoUrl(sel.id, doneRun.id)} />
              <p class="small muted">
                第 #{doneRun.id} 次生成，完成于 {fmtTime(doneRun.finished_at)}。
                产物留在服务器上，"标记已保存"只是记录状态。
              </p>
            </>
          ) : (
            (sel.runs ?? []).some((r) => r.status === 'done' && r.kind === 'analyze') && (
              <p class="small muted">分析完成了。看一眼描述，满意就点"生成视频"；分析结果本身不是视频，不在这里播放。</p>
            )
          )}
        </div>
      )}
    </>
  );
}
