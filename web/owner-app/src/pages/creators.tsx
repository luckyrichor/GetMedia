/** 5d：博主批量。
 *
 * 预览走 `/v2/creators/preview`（不落库，只告诉你"会选多少条、大约多大"），
 * 建任务走 `/v2/creators/jobs`；任务由服务端跑，页面只轮询进度。
 * 抖音列表走浏览器桥接（页面自己算签名）；未配置桥接时会被 Argus 挡，页面对此给解释，不把它说成"网络问题"。
 * 条目支持直接下载到本机：服务端用扫描时存下的播放地址转发，不落盘、不经过手机。
 */
import { useEffect, useRef, useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';
import {
  creatorItemUrl, ownerApi, type CreatorItem, type CreatorJob, type CreatorPreview, type JobStorage,
  type MediaType, type SelectionMode,
} from '../api';
import { ITEM, JOB, SELECTION, STORAGE, TONE, platformName } from '../labels';
import { Empty, ErrorBox, Field, fmtBytes, fmtDuration, fmtTime, Loading, relTime } from '../ui';

const STORAGE_OPTS: JobStorage[] = ['phone', 'metadata', 'archive', 'cache'];

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
  url: 'https://space.bilibili.com/12345 或抖音主页链接',
  douyin: '纯数字抖音号，例如 67194609689',
  bilibili: '纯数字 B 站 UID，例如 12345',
};

function fmtDay(ts: number | null): string {
  if (!ts) return '';
  const d = new Date(ts * 1000);
  const two = (x: number) => String(x).padStart(2, '0');
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}`;
}

export function CreatorsPage() {
  const [kind, setKind] = useState<CreatorKind>('url');
  const [url, setUrl] = useState('');
  const [mode, setMode] = useState<SelectionMode>('latest_n');
  const [n, setN] = useState('20');
  const [since, setSince] = useState('');
  const [until, setUntil] = useState('');
  const [pinned, setPinned] = useState(true);
  const [storage, setStorage] = useState<JobStorage>('phone');
  const [mediaType, setMediaType] = useState<MediaType>('video');
  const [preview, setPreview] = useState<CreatorPreview | null>(null);
  const [busy, setBusy] = useState<'' | 'preview' | 'create' | 'act'>('');
  const [err, setErr] = useState('');
  const [jobs, setJobs] = useState<CreatorJob[]>([]);
  const [jobsPhase, setJobsPhase] = useState<'loading' | 'ok' | 'error'>('loading');
  const [jobsErr, setJobsErr] = useState('');
  const [openId, setOpenId] = useState<number | null>(null);
  const [hidden, setHidden] = useState<number[]>(() => readHidden());
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
    return {
      url: url.trim(),
      // 链接走 resolve（由后端按域名判平台）；选「抖音号 / B 站号」时把平台显式带上，
      // 纯数字就不会被默认当成抖音号。
      platform: kind === 'url' ? null : kind,
      mode,
      n: mode === 'latest_n' ? Number(n) || null : null,
      since: mode === 'date_range' ? since || null : null,
      until: mode === 'date_range' ? until || null : null,
      include_pinned: pinned,
    };
  }

  function check(): boolean {
    const value = url.trim();
    if (!value) {
      setErr(kind === 'url' ? '先填博主主页链接' : `先填${KIND_LABEL[kind]}`);
      return false;
    }
    if (kind !== 'url' && !/^\d+$/.test(value)) {
      setErr(`${KIND_LABEL[kind]}只能填纯数字`);
      return false;
    }
    if (mode === 'latest_n' && !(Number(n) > 0)) {
      setErr('最近 N 条要填一个正整数');
      return false;
    }
    if (mode === 'date_range' && !since) {
      setErr('日期区间要至少填开始日期（北京时间）');
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

  const shownJobs = jobs.filter((j) => !hidden.includes(j.id));
  // 只数当前列表里真的被隐藏的：陈旧的 id 不该让「已隐藏 N 个」虚高
  const hiddenCount = jobs.filter((j) => hidden.includes(j.id)).length;

  return (
    <>
      <h1>博主批量</h1>
      <p class="lead">
        选一个博主，按"最近 N 条 / 日期区间 / 全部"把作品批量入库。列表抓取在服务器上做，
        所以抖音在这台机器上通常拿不到列表（403）；这种情况改用手机端建任务——手机列作品、服务器只负责下载记账。
      </p>

      <div class="panel-card">
        <div class="field-row">
          <label class="field">
            <span>类型</span>
            <select value={kind} onChange={(e) => setKind((e.target as HTMLSelectElement).value as CreatorKind)}>
              {(Object.keys(KIND_LABEL) as CreatorKind[]).map((k) => (
                <option value={k}>{KIND_LABEL[k]}</option>
              ))}
            </select>
          </label>
          <label class="field">
            <span>{KIND_LABEL[kind]}</span>
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
          <label class="field">
            <span>选取方式</span>
            <select value={mode} onChange={(e) => setMode((e.target as HTMLSelectElement).value as SelectionMode)}>
              <option value="latest_n">{SELECTION.latest_n}</option>
              <option value="date_range">{SELECTION.date_range}</option>
              <option value="all">{SELECTION.all}</option>
            </select>
          </label>
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
                <input type="date" value={since} onInput={(e) => setSince((e.target as HTMLInputElement).value)} />
              </label>
              <label class="field">
                <span>结束日期（可空）</span>
                <input type="date" value={until} onInput={(e) => setUntil((e.target as HTMLInputElement).value)} />
              </label>
            </>
          )}
        </div>

        <div class="field-row">
          <label class="field">
            <span>存放方式</span>
            <select value={storage} onChange={(e) => setStorage((e.target as HTMLSelectElement).value as JobStorage)}>
              {STORAGE_OPTS.map((s) => <option value={s}>{STORAGE[s]}</option>)}
            </select>
          </label>
          <label class="field">
            <span>格式</span>
            <select value={mediaType} onChange={(e) => setMediaType((e.target as HTMLSelectElement).value as MediaType)}>
              <option value="video">视频</option>
              <option value="audio">音频</option>
            </select>
          </label>
          <label class="check">
            <input type="checkbox" checked={pinned} onChange={(e) => setPinned((e.target as HTMLInputElement).checked)} />
            <span>包含置顶作品</span>
          </label>
        </div>

        <div class="row-actions">
          <button type="button" class="btn" disabled={busy !== ''} onClick={() => void doCreate()}>
            {busy === 'create' ? '正在建任务…' : '建批量任务'}
          </button>
          <button type="button" class="btn btn-ghost" disabled={busy !== ''} onClick={() => void doPreview()}>
            {busy === 'preview' ? '扫描中…' : '先预览会选哪些'}
          </button>
        </div>
        <p class="small muted">
          「手机下载」表示服务器只记元数据，文件由手机自己下；选归档/缓存才会在服务器落盘并计入配额。
          「全部作品」会做有界扫描，条数受服务端上限约束。
        </p>
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

      <h2>批量任务</h2>
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
          <strong>任务都被隐藏了</strong>
          <p class="small muted">点上面的「恢复显示」可以把它们放回来。</p>
        </Empty>
      )}
      {jobsPhase === 'ok' && shownJobs.length > 0 && (
        <ul class="jobs">
          {shownJobs.map((j, idx) => (
            <li class="job">
              <div class="job-head">
                <span class="job-no" aria-hidden="true">{idx + 1}</span>
                <span class={`tag ${TONE[j.status] ?? ''}`}>{JOB[j.status] ?? j.status}</span>
                <span class="grow ellipsis" title={j.creator_name}>{j.creator_name || j.source_url}</span>
                <span class="small muted nowrap">{relTime(j.created_at)}</span>
                <button
                  type="button"
                  class="job-hide nowrap"
                  title="只在这个浏览器里不再显示这个任务；任务本身照常跑，可随时恢复"
                  onClick={() => hideJob(j.id)}
                >
                  不再显示
                </button>
              </div>
              <div class="small muted">
                #{j.id} · {platformName(j.platform)} · {SELECTION[j.mode] ?? j.mode}
                {j.limit_n ? `（${j.limit_n} 条）` : ''}
                {j.since_ts ? ` · ${fmtDay(j.since_ts)} 起` : ''}
                {j.until_ts ? ` 至 ${fmtDay(j.until_ts)}` : ''}
                {' · '}{STORAGE[j.storage] ?? j.storage}
                {' · '}{j.media_type === 'audio' ? '音频' : '视频'}
              </div>
              <div class="small">
                进度：{j.done + j.failed} / {j.total || '?'}
                {j.failed > 0 ? `（失败 ${j.failed}）` : ''}
                {j.estimated_bytes > 0 ? ` · 约 ${fmtBytes(j.estimated_bytes)}` : ''}
                {j.pause_reason ? ` · 暂停原因：${j.pause_reason}` : ''}
              </div>
              <div class="row-actions">
                <button type="button" class="btn btn-ghost small" onClick={() => setOpenId(openId === j.id ? null : j.id)}>
                  {openId === j.id ? '收起条目' : '看条目'}
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

function JobItems({ jobId }: { jobId: number }) {
  const [items, setItems] = useState<CreatorItem[]>([]);
  const [total, setTotal] = useState(0);
  const [phase, setPhase] = useState<'loading' | 'ok' | 'error'>('loading');
  const [err, setErr] = useState('');

  async function page(offset: number) {
    try {
      const out = await ownerApi.creatorItems(jobId, offset);
      setItems((prev) => (offset === 0 ? out.items : [...prev, ...out.items]));
      setTotal(out.total);
      setPhase('ok');
    } catch (e) {
      setErr(messageFor(e));
      setPhase('error');
    }
  }

  useEffect(() => {
    void page(0);
  }, [jobId]);

  if (phase === 'error') return <ErrorBox message={err} retry={() => void page(0)} />;
  if (phase === 'loading') return <Loading what="任务条目" />;

  /** 逐条触发浏览器下载：每条一次请求，服务端转发完就关，不在服务器落盘。 */
  function downloadAll() {
    const todo = items.filter((i) => i.video_id);
    if (!todo.length) return;
    if (!confirm(`要把这 ${todo.length} 条逐个下载到本机吗？\n\n浏览器第一次会询问"是否允许下载多个文件"，请选允许。`)) return;
    todo.forEach((i, idx) => {
      // 拉开间隔，否则浏览器会把连续点击当成弹窗滥用直接拦掉
      setTimeout(() => {
        const a = document.createElement('a');
        a.href = creatorItemUrl(jobId, i.video_id);
        a.download = '';
        document.body.appendChild(a);
        a.click();
        a.remove();
      }, idx * 1200);
    });
  }

  return (
    <div class="items">
      <div class="kv-grid">
        <Field label="条目总数" value={total} />
        <Field label="已入库" value={items.filter((i) => i.download_state === 'done').length} />
        <Field label="失败" value={items.filter((i) => i.download_state === 'failed').length} />
      </div>
      {items.length > 0 && (
        <div class="row-actions" style="margin-bottom:6px">
          <button type="button" class="btn btn-ghost small" onClick={downloadAll}>
            下载全部（{items.length} 条）
          </button>
          {items.length < total && (
            <span class="small muted">只含已加载的 {items.length} 条，可先「加载更多」</span>
          )}
        </div>
      )}
      <ul class="plain tight">
        {items.map((i, idx) => (
          <li class="item-row">
            <span class="item-no" aria-hidden="true">{idx + 1}</span>
            <span class={`tag ${i.download_state === 'done' ? 'ok' : i.download_state === 'failed' ? 'bad' : ''}`}>
              {ITEM[i.download_state] ?? i.download_state}
            </span>
            <span class="grow ellipsis" title={i.title}>{i.title}</span>
            <span class="small muted nowrap">
              {fmtDuration(i.duration_sec)}
              {i.published_at ? ` · ${fmtTime(i.published_at)}` : ''}
              {i.track_id ? ` · 媒体库 #${i.track_id}` : ''}
            </span>
            <a class="btn btn-ghost small nowrap" href={creatorItemUrl(jobId, i.video_id)} download>
              下载
            </a>
          </li>
        ))}
      </ul>
      {items.some((i) => i.error) && (
        <p class="small bad-text">失败原因示例：{items.find((i) => i.error)?.error}</p>
      )}
      {items.length < total && (
        <button type="button" class="btn btn-ghost small" onClick={() => void page(items.length)}>
          加载更多（还有 {total - items.length} 条）
        </button>
      )}
    </div>
  );
}
