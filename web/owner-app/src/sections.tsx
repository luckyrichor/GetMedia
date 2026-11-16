/** owner 管理台的栏目表与占位页。
 *
 * P5b 只交付"认证壳"：能判断 401/403/200、能显示 owner 身份、能退出、有完整导航。
 * 各栏目的功能在 5c–5f 接入，这里把每个栏目的**后端接口**先列清楚，避免"页面有了但不知道接什么"。
 */
import type { Me } from '@shared/api';
import type { OwnerConfig } from './api';
import { Link } from './router';

export interface Section {
  path: string;
  label: string;
  /** 交付阶段：5c 反馈与媒体库 / 5d 提取、批量、定时、Agent / 5e 授权与维护 / 5f AI */
  phase: '5c' | '5d' | '5e' | '5f';
  blurb: string;
  /** 已有的后端接口；标 `待建` 的是这一阶段要新增的 */
  endpoints: { method: string; path: string; note: string }[];
  /** 这一栏还差什么，写在页面上，避免"看起来做完了" */
  missing: string;
}

export const SECTIONS: Section[] = [
  {
    path: '/feedback',
    label: '反馈收件箱',
    phase: '5c',
    blurb: '查看用户提交的问题与图片，标记处理状态。',
    endpoints: [
      { method: 'GET', path: '/v2/feedback', note: '待建：目前只有提交接口，没有列表/详情' },
      { method: 'POST', path: '/v2/feedback/{id}/status', note: '待建：标记已处理/忽略' },
    ],
    missing: '收件箱的读接口与状态机还没做；当前只有 `POST /v2/feedback`。',
  },
  {
    path: '/library',
    label: '媒体库与播放',
    phase: '5c',
    blurb: '浏览服务器上的曲目/视频，直接播放，改标题与分类，删文件。',
    endpoints: [
      { method: 'GET', path: '/v2/tracks', note: '分页列表，支持 q/平台/分类筛选' },
      { method: 'GET', path: '/v2/media/{id}', note: '媒体流，支持 Range（拖动进度条）' },
      { method: 'PATCH', path: '/v2/tracks/{id}', note: '改标题、分类' },
      { method: 'DELETE', path: '/v2/tracks/{id}/file', note: '只删文件，保留记录' },
    ],
    missing: '播放器（`<audio>`/`<video>` + Range）与列表虚拟滚动；Range 已由服务端支持，前端未接。',
  },
  {
    path: '/extract',
    label: '服务器提取',
    phase: '5d',
    blurb: '把分享链接交给服务器解析、下载、入库，查看任务状态。',
    endpoints: [
      { method: 'POST', path: '/v2/resolve', note: '只解析不回传文件' },
      { method: 'POST', path: '/v2/extract', note: '解析 + 下载 + 入库' },
      { method: 'GET', path: '/v2/extraction-jobs', note: '任务列表与状态' },
    ],
    missing: '链接输入、任务轮询与失败重试界面；B 站可用，抖音在服务器侧预期 403（见批量页说明）。',
  },
  {
    path: '/creators',
    label: '博主批量',
    phase: '5d',
    blurb: '按三种方式选作品、批量入库，支持暂停/续跑。',
    endpoints: [
      { method: 'POST', path: '/v2/creators/preview', note: '预览：最近 N / 全部 / 日期区间' },
      { method: 'POST', path: '/v2/creators/jobs', note: '建任务' },
      { method: 'GET', path: '/v2/creators/jobs/{id}/items', note: '分段取条目' },
      { method: 'POST', path: '/v2/creators/jobs/{id}/items:import', note: '手机列作品后回传元数据（抖音默认路径）' },
      { method: 'POST', path: '/v2/creators/jobs/{id}/pause|resume', note: '断点续跑' },
    ],
    missing: '选择器与进度界面。抖音列表在服务器 IP 返回 403 属预期，页面直接走"手机列 + 服务器记账"分支。',
  },
  {
    path: '/schedules',
    label: '定时任务',
    phase: '5d',
    blurb: '给博主批量设定时，按天/周执行，可立刻跑一次。',
    endpoints: [
      { method: 'GET/POST', path: '/v2/schedules', note: '列表与新建' },
      { method: 'PATCH/DELETE', path: '/v2/schedules/{id}', note: '改与删' },
      { method: 'POST', path: '/v2/schedules/{id}/run-once', note: '立即执行一次' },
      { method: 'GET', path: '/v2/schedules/{id}/runs', note: '执行历史' },
    ],
    missing: '表单与下次执行时间展示（服务端 `next_run` 已按 max(now, next_run) 推进）。',
  },
  {
    path: '/agent',
    label: 'Agent',
    phase: '5d',
    blurb: '对话式找作品、按条件筛选并触发提取。',
    endpoints: [
      { method: 'POST', path: '/v2/agent/chat', note: '当前 engine=rules（无模型）' },
      { method: 'GET', path: '/v2/agent/conversations', note: '会话列表' },
      { method: 'GET/PUT', path: '/v2/memories', note: '偏好记忆' },
      { method: 'POST', path: '/v2/agent/actions/{id}/claim', note: '待建：方案 B 的动作闭环（schema v11）' },
    ],
    missing: '模型接入（已定方案 B：Ark + 每日 200 次）与动作闭环都还没实施；页面先只显示规则应答。',
  },
  {
    path: '/platforms',
    label: '平台授权',
    phase: '5e',
    blurb: '查看各平台凭据状态，重新授权或解绑。',
    endpoints: [
      { method: 'GET', path: '/account/platforms', note: '平台与凭据状态' },
      { method: 'GET', path: '/account/platform-bindings', note: '绑定列表' },
      { method: 'POST', path: '/account/platform-auth/start', note: '开始授权' },
      { method: 'POST', path: '/account/platform-auth/cookie', note: '导入 Cookie（可选）' },
      { method: 'POST', path: '/account/platform-bindings/{platform}/unbind', note: '解绑' },
    ],
    missing: '扫码/导入的交互；凭据保险箱不可用时（`credential_vault=false`）整页只读并提示。',
  },
  {
    path: '/maintenance',
    label: '库维护',
    phase: '5e',
    blurb: '看存储水位、清理缓存、处理孤儿记录与诊断报告。',
    endpoints: [
      { method: 'GET', path: '/v2/maintenance/storage', note: '用量与配额' },
      { method: 'POST', path: '/v2/maintenance/prune-cache', note: '按 TTL/配额清理' },
      { method: 'POST', path: '/v2/maintenance/orphans', note: '孤儿记录' },
      { method: 'GET', path: '/v2/diagnostics/extractions', note: '提取失败诊断' },
    ],
    missing: '清理前的预演（dry-run）与服务端返回的明细展示。',
  },
  {
    path: '/ai',
    label: 'AI 视频',
    phase: '5f',
    blurb: '把曲目/上传素材做成 AI 视频，查看生成进度。',
    endpoints: [
      { method: 'GET', path: '/v2/recreations', note: '项目列表' },
      { method: 'POST', path: '/v2/recreations/upload', note: '上传素材（≤200 MiB）' },
      { method: 'POST', path: '/v2/recreations/{id}/generate', note: '入队生成' },
      { method: 'GET', path: '/v2/recreations/{id}/runs/{rid}/video', note: '取产物' },
    ],
    missing: '默认关闭（`GETMEDIA_AI_ENABLED=false`）；开启前需要模型凭据与 worker，页面先只读。',
  },
];

export function sectionFor(path: string): Section | null {
  return SECTIONS.find((s) => s.path === path) ?? null;
}

const GB = 1024 ** 3;
const fmtGB = (b: number) => `${(b / GB).toFixed(b % GB === 0 ? 0 : 1)} GB`;
const fmtTTL = (s: number) => (s % 86400 === 0 ? `${s / 86400} 天` : `${Math.round(s / 3600)} 小时`);

export function Overview({ cfg, me }: { cfg: OwnerConfig; me: Me }) {
  return (
    <>
      <h1>概览</h1>
      <p class="lead">
        当前账号 <strong>{me.login_name}</strong>（{me.tier}）。这一页读到的是
        <code>/v2/config/owner</code> 的真实返回值，说明 owner 通道、会话 Cookie 与应用侧 tier 校验都通了。
      </p>

      <div class="cards">
        <div class="metric">
          <div class="metric-k">媒体缓存保留</div>
          <div class="metric-v">{fmtTTL(cfg.cache_ttl_seconds)}</div>
        </div>
        <div class="metric">
          <div class="metric-k">每账号缓存配额</div>
          <div class="metric-v">{fmtGB(cfg.cache_quota_bytes)}</div>
        </div>
        <div class="metric">
          <div class="metric-k">归档配额</div>
          <div class="metric-v">{fmtGB(cfg.archive_quota_bytes)}</div>
        </div>
        <div class="metric">
          <div class="metric-k">凭据保险箱</div>
          <div class="metric-v">{cfg.credential_vault ? '可用' : '不可用'}</div>
        </div>
        <div class="metric">
          <div class="metric-k">批量单次上限</div>
          <div class="metric-v">{cfg.creator.max_items} 条</div>
        </div>
        <div class="metric">
          <div class="metric-k">可回溯起点</div>
          <div class="metric-v">{cfg.creator.max_start} 条</div>
        </div>
      </div>

      {!cfg.credential_vault && (
        <div class="alert warn">
          凭据保险箱不可用（服务器未配置 <code>GETMEDIA_CRED_KEY</code>）。平台授权页会全程只读，
          已有的 Cookie 也无法解密。需要先在服务器上配置密钥并重启 API。
        </div>
      )}

      <h2>接下来</h2>
      <p class="muted small">
        本页是 P5b 的认证壳。左侧栏目都已就位，功能在 5c–5f 逐项接入；每一栏都写了"还差什么"，
        不会出现"点进去空白但看起来已完成"的情况。
      </p>
      <ul class="plain">
        {SECTIONS.map((s) => (
          <li>
            <Link to={s.path}>{s.label}</Link>
            <span class="tag">{s.phase}</span>
            <span class="muted small">　{s.blurb}</span>
          </li>
        ))}
      </ul>
    </>
  );
}

export function Placeholder({ section }: { section: Section }) {
  return (
    <>
      <h1>
        {section.label} <span class="tag">{section.phase}</span>
      </h1>
      <p class="lead">{section.blurb}</p>
      <div class="alert info">
        <strong>这一栏还没实现。</strong> {section.missing}
      </div>
      <h2>将要使用的接口</h2>
      <table class="tbl">
        <thead>
          <tr>
            <th>方法</th>
            <th>路径</th>
            <th>说明</th>
          </tr>
        </thead>
        <tbody>
          {section.endpoints.map((e) => (
            <tr>
              <td><code>{e.method}</code></td>
              <td><code>{e.path}</code></td>
              <td class="muted small">{e.note}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p class="small muted">
        这些都走 <code>/getmedia/owner/api</code> 通道（边缘先判 owner，应用再校验 tier），
        不经过公开页的免费白名单。
      </p>
    </>
  );
}
