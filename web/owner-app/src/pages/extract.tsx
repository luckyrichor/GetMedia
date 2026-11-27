/** 5d：单个提取（原「服务器提取」）。
 *
 * 一条作品链接或一段分享文案 → 服务端解析、下载、入库，页面只负责提交与轮询进度。
 * 两个入口：「只解析」走 `/v2/resolve`，先看一眼标题/时长/大小（不入库）；
 * 「开始提取」走 `/v2/extract`，建任务后由服务端在后台跑。
 *
 * 一条媒体记录只存一份文件，所以输出格式是**单选**（批量提取那边可以一次出多种）。
 * 下方任务列表按批量提取页的规范做成表格：状态分段 + 平台 / 类型 / 存放方式 / 昵称 / 发起时间筛选，
 * 视觉与「权限管理」「邀请码」「批量提取」同一套，见 styles.css 的「单个提取页」段。
 */
import { useEffect, useRef, useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';
import {
  downloadUrl, ownerApi, type ExtractionJob, type FormatKey, type MediaType,
  type ResolvedMedia, type Storage,
} from '../api';
import { FORMAT, FORMATS_OF, FORMAT_TIP, STORAGE, STORAGE_DESC, platformName } from '../labels';
import { Select } from '../select';
import {
  BytesCell, DateRange, DateTimeCell, ErrorBox, Loading, Segmented,
  dayEndSec, dayStartSec,
  fmtBytes, fmtDateTime, fmtDuration, relTime, useToast,
} from '../ui';

/** 三种存放方式（旧的 phone 已并入「只记清单」，不再出现在选项里）。 */
const STORAGE_OPTS: Storage[] = ['metadata', 'cache', 'archive'];

/** 状态文案与色调：与批量提取页同一套取值，语气词不另起一套。
 *  running 用「处理中」而不是「解析/下载中」——六字标签会把 76px 的状态列顶出去。 */
const STATUS_LABEL: Record<string, string> = {
  queued: '排队中',
  running: '处理中',
  done: '已入库',
  failed: '失败',
};
const STATUS_TONE: Record<string, string> = {
  queued: 'idle',
  running: 'warn',
  done: 'ok',
  failed: 'bad',
};
const STATUS_KEYS = ['queued', 'running', 'done', 'failed'] as const;

/** 表格列。宽度由 styles.css 的 `--e-*` 变量给，`<col style="width: var(--e-*)">` 引用。 */
const COLS: { label: string; cls: string; mid?: boolean; tip?: string }[] = [
  { label: '序号', cls: 'e-no' },
  { label: '作品', cls: 'e-title' },
  { label: '上传者', cls: 'e-who' },
  { label: '状态', cls: 'e-st' },
  { label: '存放与输出', cls: 'e-out' },
  { label: '占用', cls: 'e-bytes', mid: true, tip: '服务器上实际存下来的文件大小；只存元数据时没有文件' },
  { label: '发起时间', cls: 'e-at' },
  { label: '操作', cls: 'e-ops' },
];

/** 一条任务的「媒体类型 · 输出格式」：没选过格式的老任务按媒体类型的默认值显示。 */
function mediaLabel(j: ExtractionJob): string {
  const picked = (j.formats?.length ? j.formats : [FORMATS_OF[j.media_type]?.[0] ?? 'source'])
    .map((f) => FORMAT[f] ?? f)
    .join(' / ');
  return `${j.media_type === 'audio' ? '音频' : '视频'} · ${picked}`;
}

export function ExtractPage() {
  const { show: toast, node: toastNode } = useToast();

  // —— 提取表单 ——
  const [input, setInput] = useState('');
  const [mediaType, setMediaType] = useState<MediaType>('video');
  const [format, setFormat] = useState<FormatKey>('source');
  const [storage, setStorage] = useState<Storage>('metadata');
  const [resolved, setResolved] = useState<ResolvedMedia | null>(null);
  const [busy, setBusy] = useState<'' | 'resolve' | 'extract'>('');
  const [err, setErr] = useState('');

  // —— 任务列表 ——
  const [jobs, setJobs] = useState<ExtractionJob[]>([]);
  const [jobsPhase, setJobsPhase] = useState<'loading' | 'ok' | 'error'>('loading');
  const [jobsErr, setJobsErr] = useState('');

  // —— 筛选：每项为空 = 不筛；多项之间是「且」关系 ——
  const [fStatus, setFStatus] = useState('');
  const [fNick, setFNick] = useState('');
  const [fPlatform, setFPlatform] = useState('');
  const [fMedia, setFMedia] = useState('');
  const [fStorage, setFStorage] = useState('');
  /** 发起时间两端默认都为空（空 = 不筛），只填一端也生效 */
  const [fSince, setFSince] = useState('');
  const [fUntil, setFUntil] = useState('');

  const timer = useRef<number | null>(null);

  async function loadJobs(silent = false) {
    if (!silent) setJobsPhase('loading');
    try {
      setJobs(await ownerApi.extractionJobs());
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

  // 有在跑的任务才轮询，跑完就停（不留一个永远转的定时器）
  const active = jobs.some((j) => j.status === 'queued' || j.status === 'running');
  useEffect(() => {
    if (!active) {
      if (timer.current) {
        window.clearInterval(timer.current);
        timer.current = null;
      }
      return;
    }
    if (timer.current) return;
    timer.current = window.setInterval(() => void loadJobs(true), 2000);
    return () => {
      if (timer.current) {
        window.clearInterval(timer.current);
        timer.current = null;
      }
    };
  }, [active]);

  async function doResolve() {
    if (!input.trim()) {
      setErr('先把作品链接或分享文案贴进来');
      return;
    }
    setBusy('resolve');
    setErr('');
    setResolved(null);
    try {
      setResolved(await ownerApi.resolve(input.trim(), mediaType));
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy('');
    }
  }

  async function submit(text: string, media: MediaType, store: Storage, fmt: FormatKey) {
    await ownerApi.extract(text.trim(), media, store, [fmt]);
    await loadJobs(true);
  }

  async function doExtract() {
    if (!input.trim()) {
      setErr('先把作品链接或分享文案贴进来');
      return;
    }
    setBusy('extract');
    setErr('');
    try {
      await submit(input, mediaType, storage, format);
      setResolved(null);
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy('');
    }
  }

  /** 失败的任务原样再来一遍：链接、类型、存放方式、格式都沿用当时选的那套。 */
  async function retry(j: ExtractionJob) {
    const fmt = (j.formats?.[0] ?? FORMATS_OF[j.media_type]?.[0] ?? 'source') as FormatKey;
    try {
      await submit(j.input, j.media_type, j.storage as Storage, fmt);
      toast('已重新提交');
    } catch (e) {
      toast(messageFor(e), 'err');
    }
  }

  function resetFilters() {
    setFStatus('');
    setFNick('');
    setFPlatform('');
    setFMedia('');
    setFStorage('');
    setFSince('');
    setFUntil('');
  }

  /** 一条任务是否命中当前所有筛选条件；条件之间是「且」，空条件不参与。 */
  function matchesFilters(j: ExtractionJob): boolean {
    if (fStatus && j.status !== fStatus) return false;
    if (fPlatform && (j.platform ?? '') !== fPlatform) return false;
    if (fMedia && j.media_type !== fMedia) return false;
    if (fStorage && j.storage !== fStorage) return false;
    const nick = (j.uploader ?? '').trim();
    if (fNick.trim() && !nick.toLowerCase().includes(fNick.trim().toLowerCase())) return false;
    const sinceSec = dayStartSec(fSince);
    if (sinceSec != null && j.created_at < sinceSec) return false;
    const untilSec = dayEndSec(fUntil);
    if (untilSec != null && j.created_at > untilSec) return false;
    return true;
  }

  const shown = jobs.filter(matchesFilters);
  const hasFilter = fStatus !== '' || fNick.trim() !== '' || fPlatform !== ''
    || fMedia !== '' || fStorage !== '' || fSince !== '' || fUntil !== '';
  /** 状态分段的每段数量：任务是一次全量拉回来的，所以这些数字是准的。 */
  const statusCount = (s: string) => jobs.filter((j) => j.status === s).length;

  return (
    <>
      {toastNode}

      <div class="page-head">
        <h1>单个提取</h1>
      </div>

      {/* ---- 提取表单 ---- */}
      <div class="ext-form">
        <label class={err ? 'field is-bad' : 'field'}>
          <span>作品链接或分享文案</span>
          <textarea
            class="ext-area"
            rows={3}
            maxLength={4000}
            placeholder="粘贴 B 站/抖音的链接，或整段分享文案"
            aria-label="作品链接或分享文案"
            value={input}
            onInput={(e) => { setInput((e.target as HTMLTextAreaElement).value); setErr(''); }}
          />
        </label>

        <div class="ext-grid">
          <div class="field">
            <span>格式</span>
            <Select
              ariaLabel="格式"
              value={mediaType}
              options={[{ value: 'video', label: '视频' }, { value: 'audio', label: '音频' }]}
              onChange={(v) => {
                setMediaType(v as MediaType);
                // 两类格式没有交集：换类别时把输出格式收成那一类最常用的一个，不会留下无效组合
                setFormat((FORMATS_OF[v] ?? ['source'])[0] as FormatKey);
                setErr('');
              }}
            />
          </div>
          <div class="field">
            <span>输出格式</span>
            <Select
              ariaLabel="输出格式"
              value={format}
              options={(FORMATS_OF[mediaType] ?? ['source']).map((f) => ({
                value: f, label: FORMAT[f] ?? f, tip: FORMAT_TIP[f],
              }))}
              onChange={(v) => setFormat(v as FormatKey)}
            />
          </div>
          <div class="field">
            <span>存放方式</span>
            <Select
              ariaLabel="存放方式"
              value={storage}
              options={STORAGE_OPTS.map((s) => ({
                value: s, label: STORAGE[s]!, tip: STORAGE_DESC[s],
              }))}
              onChange={(v) => setStorage(v as Storage)}
            />
          </div>
        </div>

        {err ? <div class="info-bar bad" role="status">{err}</div> : null}

        {/* 解析结果：一条淡蓝信息条，点「只解析」才有；任何字段改动都会把它清掉 */}
        {resolved ? (
          <div class="info-bar" role="status">
            <div class="info-main">
              <strong>{resolved.title}</strong>
              <span class="info-who">
                {resolved.uploader || '未知 UP 主'} · {platformName(resolved.platform)} ·{' '}
                {fmtDuration(resolved.duration_sec)}
                {resolved.estimated_bytes > 0 ? ` · 约 ${fmtBytes(resolved.estimated_bytes)}` : ''}
              </span>
            </div>
            <div class="info-list">
              <span title={resolved.source_url ?? ''}>{resolved.video_id}</span>
              <span>只是「能解析」，还没有入库；要存下来点「开始提取」。</span>
            </div>
          </div>
        ) : null}

        <div class="ext-foot">
          <div class="ext-acts">
            <button type="button" class="btn btn-outline" disabled={busy !== ''} onClick={() => void doResolve()}>
              {busy === 'resolve' ? '解析中…' : '只解析，不入库'}
            </button>
            <button type="button" class="btn" disabled={busy !== ''} onClick={() => void doExtract()}>
              {busy === 'extract' ? '正在提交…' : '开始提取'}
            </button>
          </div>
        </div>
      </div>

      {/* ---- 提取任务 ---- */}
      <div class="sec-head">
        <h2>提取任务</h2>
        <span class="sec-n">共 {jobs.length} 条</span>
      </div>

      {/* 筛选栏：第一行状态分段 + 昵称，第二行平台 / 类型 / 存放方式 + 发起时间 + 重置 */}
      <div class="filter-box ex-filters">
        <div class="filter-line">
          <Segmented
            ariaLabel="按状态筛选"
            value={fStatus}
            onChange={setFStatus}
            items={[
              { key: '', label: '全部', n: jobs.length },
              ...STATUS_KEYS.map((s) => ({ key: s, label: STATUS_LABEL[s] ?? s, n: statusCount(s) })),
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
        </div>
        <div class="filter-line">
          <span class="ctrl-fixed">
            <Select
              ariaLabel="筛选：平台"
              value={fPlatform}
              options={[
                { value: '', label: '全部平台' },
                { value: 'bilibili', label: 'B 站' },
                { value: 'douyin', label: '抖音' },
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
        {jobsPhase === 'loading' ? <Loading what="提取任务" /> : null}
        {jobsPhase === 'error' ? <ErrorBox message={jobsErr} retry={() => void loadJobs()} /> : null}

        {jobsPhase === 'ok' && shown.length === 0 ? (
          <div class="empty-state">
            <p>{jobs.length === 0 ? '还没有提取任务' : '没有符合条件的任务'}</p>
            {jobs.length === 0 ? (
              <p class="small muted">上面贴一个链接，提交后任务会出现在这里，状态自动刷新。</p>
            ) : (
              <button type="button" class="btn small" onClick={resetFilters}>重置筛选</button>
            )}
          </div>
        ) : null}

        {shown.length > 0 ? (
          <div class="table-wrap">
            <table class="table cr-table ex-table">
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
                {shown.map((j, idx) => {
                  const downloadable = j.status === 'done' && !!j.track_id && j.storage !== 'metadata';
                  return (
                    <tr data-job={j.id}>
                      <td class="e-no muted">{idx + 1}</td>
                      <td class="e-title">
                        <div class="t-main" title={j.title || j.input}>{j.title || j.input}</div>
                        {/* 失败的任务把原因写在这行（不藏在 tooltip 里）：这条列宽够，
                            而且失败时作品号没什么用，位置让给原因更值 */}
                        {j.status === 'failed' && j.error_detail ? (
                          <div class="t-sub bad-text" title={j.error_detail}>{j.error_detail}</div>
                        ) : (
                          <div class="t-sub">{j.video_id || '—'}</div>
                        )}
                      </td>
                      <td class="e-who">
                        <div class="who-name" title={j.uploader ?? ''}>{j.uploader || '昵称未知'}</div>
                        <div class="who-sub">{platformName(j.platform)}</div>
                      </td>
                      <td>
                        <span class={`pill tone-${STATUS_TONE[j.status] ?? 'idle'}`} title={j.error_detail ?? undefined}>
                          {STATUS_LABEL[j.status] ?? j.status}
                        </span>
                      </td>
                      <td class="e-out">
                        <div>{STORAGE[j.storage] ?? j.storage}</div>
                        <div class="sub">{mediaLabel(j)}</div>
                      </td>
                      <td class="e-bytes mid">
                        {j.bytes && j.bytes > 0
                          ? <BytesCell n={j.bytes} />
                          : <span class="muted">—</span>}
                      </td>
                      <td class="e-at" title={`发起于 ${fmtDateTime(j.created_at)}`}>
                        <DateTimeCell at={j.created_at} />
                        <div class="sub">{relTime(j.created_at)}</div>
                      </td>
                      <td class="e-ops">
                        <div class="row-ops">
                          {downloadable ? (
                            <a class="text-btn" href={downloadUrl(j.track_id!)} download>下载</a>
                          ) : null}
                          {j.status === 'failed' ? (
                            <button type="button" class="text-btn" onClick={() => void retry(j)}>重试</button>
                          ) : null}
                          {!downloadable && j.status !== 'failed' ? <span class="muted">—</span> : null}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : null}

        {shown.length > 0 ? (
          <div class="box-foot">
            <span class="small muted">共 {shown.length} 条</span>
            {active ? <span class="foot-page small muted">有任务在跑，每 2 秒自动刷新</span> : null}
          </div>
        ) : null}
      </section>
    </>
  );
}
