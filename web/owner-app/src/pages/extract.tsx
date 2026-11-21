/** 5d：服务器提取。
 *
 * 两种用法：
 * - 「只解析」调 `/v2/resolve`，返回标题/时长/来源，不入库（先看一眼再决定）；
 * - 「提取」调 `/v2/extract`，服务端在后台解析 + 下载 + 入库，返回任务号，页面轮询任务列表。
 * 抖音在服务器 IP 上大概率被挡（403），页面直说，不假装是网络问题。
 */
import { useEffect, useRef, useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';
import { ownerApi, type ExtractionJob, type MediaType, type ResolvedMedia, type Storage } from '../api';
import { EXTRACT, STORAGE, STORAGE_DESC, platformName } from '../labels';
import { Select } from '../select';
import { Empty, ErrorBox, fmtBytes, fmtDuration, fmtTime, Loading, relTime } from '../ui';

const STORAGE_OPTS: Storage[] = ['metadata', 'cache', 'archive'];

export function ExtractPage() {
  const [input, setInput] = useState('');
  const [mediaType, setMediaType] = useState<MediaType>('video');
  const [storage, setStorage] = useState<Storage>('metadata');
  const [resolved, setResolved] = useState<ResolvedMedia | null>(null);
  const [busy, setBusy] = useState<'' | 'resolve' | 'extract'>('');
  const [err, setErr] = useState('');
  const [jobs, setJobs] = useState<ExtractionJob[]>([]);
  const [jobsPhase, setJobsPhase] = useState<'loading' | 'ok' | 'error'>('loading');
  const [jobsErr, setJobsErr] = useState('');
  const timer = useRef<number | null>(null);

  async function loadJobs(silent = false) {
    if (!silent) setJobsPhase('loading');
    try {
      const rows = await ownerApi.extractionJobs();
      setJobs(rows);
      setJobsPhase('ok');
      setJobsErr('');
      return rows;
    } catch (e) {
      setJobsErr(messageFor(e));
      setJobsPhase('error');
      return [];
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

  async function doExtract() {
    if (!input.trim()) {
      setErr('先把作品链接或分享文案贴进来');
      return;
    }
    setBusy('extract');
    setErr('');
    try {
      await ownerApi.extract(input.trim(), mediaType, storage);
      setResolved(null);
      await loadJobs(true);
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy('');
    }
  }

  return (
    <>
      <h1>服务器提取</h1>
      <p class="lead">
        把链接交给服务器解析、下载、入库。这一步在服务器 IP 上执行，所以平台看到的是服务器的地址；
        抖音目前在这台机器上会被挡（返回 403 风控），B 站正常。
      </p>

      <div class="panel-card">
        <label class="field">
          <span>作品链接或分享文案</span>
          <textarea
            rows={3}
            maxLength={4000}
            placeholder="粘贴 B 站/抖音的链接，或整段分享文案（含链接即可）"
            value={input}
            onInput={(e) => setInput((e.target as HTMLTextAreaElement).value)}
          />
        </label>

        <div class="field-row">
          <div class="field">
            <span>要的格式</span>
            <Select
              ariaLabel="要的格式"
              value={mediaType}
              options={[
                { value: 'video', label: '视频', hint: '清晰度由平台给' },
                { value: 'audio', label: '音频', hint: '只取音轨' },
              ]}
              onChange={(v) => setMediaType(v as MediaType)}
            />
          </div>
          <div class="field">
            <span>存放方式</span>
            <Select
              ariaLabel="存放方式"
              value={storage}
              options={STORAGE_OPTS.map((s) => ({ value: s, label: STORAGE[s]! }))}
              onChange={(v) => setStorage(v as Storage)}
            />
          </div>
        </div>

        <div class="row-actions">
          <button type="button" class="btn" disabled={busy !== ''} onClick={() => void doExtract()}>
            {busy === 'extract' ? '已提交，正在排队…' : '提取并入库'}
          </button>
          <button type="button" class="btn btn-ghost" disabled={busy !== ''} onClick={() => void doResolve()}>
            {busy === 'resolve' ? '解析中…' : '只解析，不入库'}
          </button>
        </div>
        <ul class="storage-notes small muted">
          {STORAGE_OPTS.map((s) => (
            <li class={s === storage ? 'on' : ''}>
              <strong>{STORAGE[s]}</strong>：{STORAGE_DESC[s]}
            </li>
          ))}
        </ul>
        {err && <div class="alert warn small">{err}</div>}
      </div>

      {resolved && (
        <div class="result">
          <div class="result-head">
            <strong>解析结果</strong>
            <span class="tag">{platformName(resolved.platform)}</span>
            <span class="tag">{resolved.video_id}</span>
          </div>
          <div class="result-body">
            {resolved.cover_url && <img class="cover" src={resolved.cover_url} alt="" loading="lazy" />}
            <div>
              <div class="result-title">{resolved.title}</div>
              <div class="small muted">
                {resolved.uploader || '未知 UP 主'} · {fmtDuration(resolved.duration_sec)}
                {resolved.estimated_bytes > 0 ? ` · 约 ${fmtBytes(resolved.estimated_bytes)}` : ''}
              </div>
              <p class="small muted">
                这里只是"能解析"，还没有入库。要存下来点上面的「提取并入库」。
              </p>
            </div>
          </div>
        </div>
      )}

      <h2>提取任务</h2>
      {jobsPhase === 'loading' && <Loading what="提取任务" />}
      {jobsPhase === 'error' && <ErrorBox message={jobsErr} retry={() => void loadJobs()} />}
      {jobsPhase === 'ok' && jobs.length === 0 && (
        <Empty>
          <strong>还没有提取任务</strong>
          <p class="small muted">上面提交一个链接，任务会出现在这里，状态自动刷新。</p>
        </Empty>
      )}
      {jobsPhase === 'ok' && jobs.length > 0 && (
        <>
          <ul class="jobs">
            {jobs.map((j) => (
              <li class="job">
                <div class="job-head">
                  <span class={`tag ${j.status === 'done' ? 'ok' : j.status === 'failed' ? 'bad' : ''}`}>
                    {EXTRACT[j.status] ?? j.status}
                  </span>
                  <span class="grow ellipsis" title={j.input}>{j.input}</span>
                  <span class="small muted nowrap">{relTime(j.created_at)}</span>
                </div>
                <div class="small muted">
                  #{j.id} · {j.media_type === 'audio' ? '音频' : '视频'} · {STORAGE[j.storage] ?? j.storage}
                  {j.platform ? ` · ${platformName(j.platform)}` : ''}
                  {j.video_id ? ` · ${j.video_id}` : ''}
                  {j.track_id ? ` · 媒体库 #${j.track_id}` : ''}
                  {j.finished_at ? ` · 完成于 ${fmtTime(j.finished_at)}` : ''}
                </div>
                {j.error_detail && <div class="small bad-text">{j.error_detail}</div>}
              </li>
            ))}
          </ul>
          {active && <p class="small muted">有任务在跑，每 2 秒自动刷新一次；全部结束后停止。</p>}
        </>
      )}
    </>
  );
}
