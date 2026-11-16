/** 5e：库维护。
 *
 * 三件事：看占用（归档/缓存 对配额）、清理（过期缓存 / 没有记录指向的孤儿文件）、看提取失败上报。
 * 清理接口是**全库**操作（不按账号过滤）——服务器上只有 owner 会用服务端库，页面上写明这一点。
 */
import { useEffect, useState } from 'preact/hooks';
import { messageFor } from '@shared/errors';
import { ownerApi, type DiagReport, type StorageReport } from '../api';
import { platformName } from '../labels';
import { Empty, ErrorBox, Field, fmtBytes, fmtTime, Loading } from '../ui';

const STORAGE_LABEL: Record<string, string> = { metadata: '仅元数据', archive: '归档', cache: '缓存' };

export function MaintenancePage() {
  const [st, setSt] = useState<StorageReport | null>(null);
  const [reports, setReports] = useState<DiagReport[]>([]);
  const [phase, setPhase] = useState<'loading' | 'ok' | 'error'>('loading');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState('');

  async function load() {
    setPhase('loading');
    setErr('');
    try {
      const [s, r] = await Promise.all([ownerApi.storage(), ownerApi.diagnostics()]);
      setSt(s);
      setReports(r);
      setPhase('ok');
    } catch (e) {
      setErr(messageFor(e));
      setPhase('error');
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function prune() {
    if (!confirm('清理过期缓存？会把过期的缓存文件删掉，记录退回"仅元数据"。正在播放的最近 5 分钟内访问过的会跳过。')) return;
    setBusy(true);
    setErr('');
    try {
      const out = await ownerApi.pruneCache();
      setNote(`清理完成：${out.rows} 条记录，释放 ${fmtBytes(out.bytes)}。`);
      await load();
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  async function orphans() {
    if (!confirm('清理孤儿文件？会删除归档/缓存目录里没有记录指向的文件（迁移或异常退出的残留）。')) return;
    setBusy(true);
    setErr('');
    try {
      const out = await ownerApi.purgeOrphans();
      setNote(`清理完成：删除 ${out.removed} 个没有记录指向的文件。`);
      await load();
    } catch (e) {
      setErr(messageFor(e));
    } finally {
      setBusy(false);
    }
  }

  if (phase === 'loading') return <Loading what="库状态" />;

  return (
    <>
      <h1>库维护</h1>
      <p class="lead">
        看服务端媒体占用了多少、有没有该清的缓存与孤儿文件，以及最近的提取失败上报。
      </p>

      {phase === 'error' && <ErrorBox message={err} retry={() => void load()} />}

      {st && (
        <>
          <h2>占用</h2>
          <div class="cards">
            {(['archive', 'cache'] as const).map((k) => {
              const used = st.used[k];
              const quota = st.quota[k];
              const pct = quota > 0 ? Math.min(100, Math.round((used / quota) * 100)) : 0;
              return (
                <div class="metric">
                  <div class="metric-k">{k === 'archive' ? '归档' : '缓存'}</div>
                  <div class="metric-v">{fmtBytes(used)} <span class="small muted">/ {fmtBytes(quota)}</span></div>
                  <div class="bar" role="img" aria-label={`${pct}%`}><i style={`width:${pct}%`} /></div>
                  <div class="small muted">{pct}% 已用</div>
                </div>
              );
            })}
          </div>

          <table class="tbl">
            <thead>
              <tr><th>存放方式</th><th>条目</th><th>占用</th></tr>
            </thead>
            <tbody>
              {Object.entries(st.by_storage).map(([k, v]) => (
                <tr>
                  <td>{STORAGE_LABEL[k] ?? k}</td>
                  <td>{v.tracks}</td>
                  <td>{fmtBytes(v.bytes)}</td>
                </tr>
              ))}
              {Object.keys(st.by_storage).length === 0 && (
                <tr><td colSpan={3} class="muted">媒体库里还没有条目。</td></tr>
              )}
            </tbody>
          </table>

          <h2>清理</h2>
          <div class="row-actions">
            <button type="button" class="btn" disabled={busy} onClick={() => void prune()}>清理过期缓存</button>
            <button type="button" class="btn btn-ghost" disabled={busy} onClick={() => void orphans()}>
              清理孤儿文件
            </button>
          </div>
          <p class="small muted">
            这两个操作是<strong>全库</strong>的，不按账号过滤——服务器上只有 owner 会用到服务端媒体库。
            删除文件不会删除记录（记录退回"仅元数据"），播放时会重新解析。
          </p>
        </>
      )}

      {note && <div class="alert info small">{note}</div>}
      {err && phase === 'ok' && <div class="alert warn small">{err}</div>}

      <h2>提取失败上报</h2>
      <p class="small muted">
        手机端提取失败时上报的记录（最近 50 条）。只用于定位问题，不含任何 Cookie 或令牌。
      </p>
      {reports.length === 0 ? (
        <Empty>
          <strong>没有失败上报</strong>
          <p class="small muted">这通常说明最近没有失败的提取。</p>
        </Empty>
      ) : (
        <ul class="jobs">
          {reports.map((r) => (
            <li class="job">
              <div class="job-head">
                <span class="tag bad">{r.error_code || r.stage}</span>
                <span class="grow ellipsis" title={r.detail}>{r.detail || '(无补充说明)'}</span>
                <span class="small muted nowrap">{fmtTime(r.created_at)}</span>
              </div>
              <div class="small muted">
                #{r.id} · {platformName(r.platform)} · {r.video_id}
                {r.track_id ? ` · 媒体库 #${r.track_id}` : ''}
                {r.client_version ? ` · v${r.client_version}` : ''}
                {' · 阶段：'}{r.stage}
              </div>
            </li>
          ))}
        </ul>
      )}

      {st && (
        <div class="kv-grid">
          <Field label="缓存保留" value="按服务端 TTL（见概览）" />
          <Field label="最近刷新" value={fmtTime(Math.floor(Date.now() / 1000))} />
        </div>
      )}
    </>
  );
}
