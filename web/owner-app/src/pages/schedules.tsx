/** 5d：定时抓取。
 *
 * 服务端用 `interval_minutes` 而不是 cron：网页可安全改写，不需要解析器；每次触发只是建一个批量任务，
 * 进度要去「博主批量」看。页面负责：建、开关、立刻跑一次、看历史、删。
 */
import { useEffect, useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';
import { ownerApi, type JobStorage, type MediaType, type Schedule, type ScheduleRun, type SelectionMode } from '../api';
import { SELECTION, STORAGE, intervalText, platformName } from '../labels';
import { Empty, ErrorBox, fmtTime, Loading, relTime } from '../ui';

const INTERVALS = [60, 360, 720, 1440, 4320, 10080];

export function SchedulesPage() {
  const [rows, setRows] = useState<Schedule[]>([]);
  const [phase, setPhase] = useState<'loading' | 'ok' | 'error'>('loading');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const [openId, setOpenId] = useState<number | null>(null);

  const [name, setName] = useState('');
  const [platform, setPlatform] = useState('bilibili');
  const [input, setInput] = useState('');
  const [mode, setMode] = useState<SelectionMode>('latest_n');
  const [n, setN] = useState('20');
  const [since, setSince] = useState('');
  const [until, setUntil] = useState('');
  const [every, setEvery] = useState('1440');
  const [storage, setStorage] = useState<JobStorage>('phone');
  const [mediaType, setMediaType] = useState<MediaType>('video');
  const [pinned, setPinned] = useState(true);

  async function load() {
    setPhase('loading');
    try {
      setRows(await ownerApi.schedules());
      setPhase('ok');
      setErr('');
    } catch (e) {
      setErr(messageFor(e));
      setPhase('error');
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function create() {
    if (!name.trim() || !input.trim()) {
      setErr('名字和博主要都填');
      return;
    }
    if (mode === 'latest_n' && !(Number(n) > 0)) {
      setErr('最近 N 条要填一个正整数');
      return;
    }
    setBusy(true);
    setErr('');
    try {
      await ownerApi.createSchedule({
        name: name.trim(),
        platform,
        input: input.trim(),
        mode,
        interval_minutes: Number(every),
        n: mode === 'latest_n' ? Number(n) : null,
        since: mode === 'date_range' ? since || null : null,
        until: mode === 'date_range' ? until || null : null,
        include_pinned: pinned,
        storage,
        media_type: mediaType,
      });
      setName('');
      setInput('');
      await load();
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function toggle(s: Schedule) {
    setBusy(true);
    setErr('');
    try {
      const next = await ownerApi.patchSchedule(s.id, { enabled: !s.enabled });
      setRows((prev) => prev.map((x) => (x.id === next.id ? next : x)));
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function runOnce(s: Schedule) {
    setBusy(true);
    setErr('');
    try {
      await ownerApi.runSchedule(s.id);
      await load();
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function remove(s: Schedule) {
    if (!confirm(`删除定时任务「${s.name}」？已经跑出来的批量任务不受影响。`)) return;
    setBusy(true);
    setErr('');
    try {
      await ownerApi.deleteSchedule(s.id);
      setRows((prev) => prev.filter((x) => x.id !== s.id));
      if (openId === s.id) setOpenId(null);
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <h1>定时任务</h1>
      <p class="lead">
        给一个博主设"每 N 小时/天检查一次，有新作品就建批量任务"。触发只是建任务，
        下载进度和失败条目去「博主批量」看。下一次执行时间由服务端算，页面只显示。
      </p>

      <div class="panel-card">
        <div class="field-row">
          <label class="field">
            <span>名字（唯一）</span>
            <input type="text" maxLength={80} value={name} onInput={(e) => setName((e.target as HTMLInputElement).value)} />
          </label>
          <label class="field">
            <span>平台</span>
            <select value={platform} onChange={(e) => setPlatform((e.target as HTMLSelectElement).value)}>
              <option value="bilibili">B 站</option>
              <option value="douyin">抖音</option>
            </select>
          </label>
          <label class="field">
            <span>间隔</span>
            <select value={every} onChange={(e) => setEvery((e.target as HTMLSelectElement).value)}>
              {INTERVALS.map((m) => <option value={String(m)}>{intervalText(m)}</option>)}
            </select>
          </label>
        </div>

        <label class="field">
          <span>博主主页链接 / UID / 抖音号</span>
          <input type="text" maxLength={2000} value={input} onInput={(e) => setInput((e.target as HTMLInputElement).value)} />
        </label>

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
              <input type="number" min={1} value={n} onInput={(e) => setN((e.target as HTMLInputElement).value)} />
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
          <label class="field">
            <span>存放</span>
            <select value={storage} onChange={(e) => setStorage((e.target as HTMLSelectElement).value as JobStorage)}>
              {(['phone', 'metadata', 'archive', 'cache'] as JobStorage[]).map((s) => <option value={s}>{STORAGE[s]}</option>)}
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
            <span>包含置顶</span>
          </label>
        </div>

        <div class="row-actions">
          <button type="button" class="btn" disabled={busy} onClick={() => void create()}>新建定时任务</button>
        </div>
        <p class="small muted">
          间隔最短 15 分钟、最长 7 天。抖音列表在服务器 IP 上会被挡（403），这个定时任务同样拿不到列表——
          需要抖音的话，用手机端批量。
        </p>
        {err && <div class="alert warn small">{err}</div>}
      </div>

      <h2>已有定时任务</h2>
      {phase === 'loading' && <Loading what="定时任务" />}
      {phase === 'error' && <ErrorBox message={err} retry={() => void load()} />}
      {phase === 'ok' && rows.length === 0 && (
        <Empty>
          <strong>还没有定时任务</strong>
          <p class="small muted">上面建一个，最快下一次就是一小时后（或你选的间隔）。</p>
        </Empty>
      )}
      {phase === 'ok' && rows.length > 0 && (
        <ul class="jobs">
          {rows.map((s) => (
            <li class={s.enabled ? 'job' : 'job off'}>
              <div class="job-head">
                <span class={`tag ${s.enabled ? 'ok' : ''}`}>{s.enabled ? '已启用' : '已停用'}</span>
                <span class="grow ellipsis" title={s.name}>{s.name}</span>
                <span class="small muted nowrap">{intervalText(s.interval_minutes)}</span>
              </div>
              <div class="small muted">
                {platformName(s.platform)} · {SELECTION[s.mode] ?? s.mode}
                {s.limit_n ? `（${s.limit_n} 条）` : ''} · {STORAGE[s.storage] ?? s.storage}
                {' · '}{s.media_type === 'audio' ? '音频' : '视频'}
              </div>
              <div class="small">
                下次执行：{s.enabled ? (s.next_run ? `${fmtTime(s.next_run)}（${relTime(s.next_run)}）` : '未排期') : '已停用，不会执行'}
                {s.last_run ? ` · 上次：${fmtTime(s.last_run)}${s.last_status ? `（${s.last_status}）` : ''}` : ''}
                {s.last_job_id ? ` · 上次任务 #${s.last_job_id}` : ''}
              </div>
              <div class="row-actions">
                <button type="button" class="btn btn-ghost small" disabled={busy} onClick={() => void toggle(s)}>
                  {s.enabled ? '停用' : '启用'}
                </button>
                <button type="button" class="btn btn-ghost small" disabled={busy} onClick={() => void runOnce(s)}>
                  立刻跑一次
                </button>
                <button type="button" class="btn btn-ghost small" onClick={() => setOpenId(openId === s.id ? null : s.id)}>
                  {openId === s.id ? '收起历史' : '执行历史'}
                </button>
                <button type="button" class="btn btn-ghost small" disabled={busy} onClick={() => void remove(s)}>
                  删除
                </button>
              </div>
              {openId === s.id && <Runs id={s.id} />}
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

function Runs({ id }: { id: number }) {
  const [runs, setRuns] = useState<ScheduleRun[] | null>(null);
  const [err, setErr] = useState('');

  useEffect(() => {
    let alive = true;
    ownerApi.scheduleRuns(id).then(
      (r) => alive && setRuns(r),
      (e) => alive && setErr(messageFor(e)),
    );
    return () => {
      alive = false;
    };
  }, [id]);

  if (err) return <div class="alert warn small">{err}</div>;
  if (!runs) return <Loading what="执行历史" />;
  if (runs.length === 0) return <p class="small muted">还没有执行记录。</p>;

  return (
    <ul class="plain tight">
      {runs.map((r, i) => (
        <li class="item-row">
          <span class="tag">{String(r.status ?? '—')}</span>
          <span class="grow small">
            {r.job_id != null ? `任务 #${r.job_id}` : `第 ${i + 1} 次`}
            {r.started_at ? ` · 开始 ${fmtTime(r.started_at as number)}` : ''}
            {r.finished_at ? ` · 结束 ${fmtTime(r.finished_at as number)}` : ''}
          </span>
        </li>
      ))}
    </ul>
  );
}
