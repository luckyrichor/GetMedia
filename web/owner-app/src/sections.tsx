/** owner 管理台的栏目表与占位页。
 *
 * 5c 的「反馈收件箱」「媒体库与播放」已经接了真实页面（下方 `done: true`），其余栏目仍是占位页：
 * 每个占位页都把**后端接口**和**还差什么**写清楚，避免"页面有了但不知道接什么"。
 */
import type { Me } from '@shared/api';
import type { OwnerConfig } from './api';
import { Link } from './router';

export interface Section {
  path: string;
  label: string;
  /** 交付阶段：5c 反馈与媒体库 / 5d 提取、批量、定时、Agent / 5e 授权与维护 / 5f AI */
  phase: '5c' | '5d' | '5e' | '5f';
  /** 这一栏已经有真实页面（否则渲染占位页） */
  done?: boolean;
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
    done: true,
    blurb: '查看用户提交的问题与图片，标记处理状态。',
    endpoints: [
      { method: 'GET', path: '/v2/feedback', note: '分页 + 状态筛选 + 各状态计数' },
      { method: 'GET', path: '/v2/feedback/{id}', note: '正文、诊断、图片清单' },
      { method: 'GET', path: '/v2/feedback/{id}/images/{n}', note: '按序号取图，不接受文件名' },
      { method: 'POST', path: '/v2/feedback/{id}/status', note: 'new / handled / ignored，同值幂等' },
    ],
    missing: '不能回复用户（反馈没有回信通道）、不能搜索正文、不能批量处理；保留期清理仍由后台任务按 90/180 天执行。',
  },
  {
    path: '/library',
    label: '媒体库与播放',
    phase: '5c',
    done: true,
    blurb: '浏览服务器上的曲目/视频，直接播放，改标题，删文件或记录。',
    endpoints: [
      { method: 'GET', path: '/v2/tracks', note: '游标翻页（since_seq），按当前账号' },
      { method: 'GET', path: '/v2/media/{id}', note: '媒体流，支持 Range（拖动进度条）' },
      { method: 'PATCH', path: '/v2/tracks/{id}', note: '改标题/UP 主/置顶' },
      { method: 'DELETE', path: '/v2/tracks/{id}/file', note: '只删文件，记录退回"仅元数据"' },
      { method: 'DELETE', path: '/v2/tracks/{id}', note: '逻辑删除，会同步到手机端' },
    ],
    missing: '不能上传或替换文件、不能新建播放列表；条目没有"分类"字段（旧版的分类没有迁过来）。',
  },
  {
    path: '/extract',
    label: '服务器提取',
    phase: '5d',
    done: true,
    blurb: '把分享链接交给服务器解析、下载、入库，查看任务状态。',
    endpoints: [
      { method: 'POST', path: '/v2/resolve', note: '只解析，不入库（先看一眼）' },
      { method: 'POST', path: '/v2/extract', note: '解析 + 下载 + 入库，返回任务号' },
      { method: 'GET', path: '/v2/extraction-jobs', note: '任务列表；有在跑的任务时页面每 2 秒刷新' },
    ],
    missing: '没有单条任务详情页，也**没有失败重试按钮**（失败要回填链接重新提交）；抖音在服务器 IP 会被挡（403），页面直说。',
  },
  {
    path: '/creators',
    label: '博主批量',
    phase: '5d',
    done: true,
    blurb: '按三种方式选作品、批量入库，支持暂停/续跑/重试。',
    endpoints: [
      { method: 'POST', path: '/v2/creators/preview', note: '预览会选哪些、大约多大' },
      { method: 'POST', path: '/v2/creators/jobs', note: '建任务（latest_n / date_range / all）' },
      { method: 'GET', path: '/v2/creators/jobs/{id}/items', note: '分段取条目与每个条目的状态' },
      { method: 'POST', path: '/v2/creators/jobs/{id}/{pause,resume,cancel,retry}', note: '暂停、继续、取消、重试失败条目' },
    ],
    missing: '不做"手机列作品后回传"（`items:import`）——那是手机端在抖音 403 时的路径，网页没有手机相册/网络环境；也不做单条跳过/重下的精细操作。',
  },
  {
    path: '/schedules',
    label: '定时任务',
    phase: '5d',
    done: true,
    blurb: '给博主批量设定时，按小时/天执行，可立刻跑一次。',
    endpoints: [
      { method: 'GET', path: '/v2/schedules', note: '列表（含 next_run / last_status）' },
      { method: 'POST', path: '/v2/schedules', note: '新建（间隔 15 分钟–7 天）' },
      { method: 'PATCH', path: '/v2/schedules/{id}', note: '启用/停用' },
      { method: 'POST', path: '/v2/schedules/{id}/run-once', note: '立刻跑一次' },
      { method: 'GET', path: '/v2/schedules/{id}/runs', note: '执行历史' },
    ],
    missing: '不能改间隔与选取条件（要改只能删了重建）；执行历史只有服务端返回的那几列，没有失败原因详情。',
  },
  {
    path: '/agent',
    label: 'Agent',
    phase: '5d',
    done: true,
    blurb: '对话式找作品、按条件筛选；模型没配好时退回规则应答。',
    endpoints: [
      { method: 'GET', path: '/v2/agent/status', note: '当前引擎、今日调用/上限、模型名' },
      { method: 'POST', path: '/v2/agent/chat', note: '发消息；带作品链接时服务端建一条待领取动作' },
      { method: 'GET', path: '/v2/agent/conversations/{id}', note: '会话消息与这一轮的动作' },
      { method: 'GET/PUT/DELETE', path: '/v2/memories', note: '偏好记忆增删改' },
    ],
    missing: '网页**不领取动作**（claim 由手机端做，代领会把状态推到进行中却没人执行），也不做流式输出；模型未配置时只有规则应答。',
  },
  {
    path: '/platforms',
    label: '平台授权',
    phase: '5e',
    done: true,
    blurb: '查看各平台凭据状态，重新授权或解绑。',
    endpoints: [
      { method: 'GET', path: '/account/platforms', note: '平台、可用授权方式、OAuth 是否配置' },
      { method: 'GET', path: '/account/platform-bindings', note: '绑定状态 + 凭据状态（不含 Cookie）' },
      { method: 'POST', path: '/account/platform-auth/start', note: '建授权会话（10 分钟）' },
      { method: 'POST', path: '/account/platform-auth/cookie', note: '导入 Cookie，加密入库 + 写 600 权限文件' },
      { method: 'POST', path: '/account/platform-bindings/{platform}/unbind', note: '解绑，不动媒体数据' },
    ],
    missing: '抖音开放平台 OAuth 未配置时只能走网页登录或导入 Cookie；网页不显示、也取不到任何 Cookie 明文（服务端本来就不返回）。',
  },
  {
    path: '/maintenance',
    label: '库维护',
    phase: '5e',
    done: true,
    blurb: '看存储水位、清理缓存与孤儿文件、看提取失败上报。',
    endpoints: [
      { method: 'GET', path: '/v2/maintenance/storage', note: '按存放方式的条目与字节、配额与已用' },
      { method: 'POST', path: '/v2/maintenance/prune-cache', note: '删过期缓存，顺手退回"仅元数据"' },
      { method: 'POST', path: '/v2/maintenance/orphans', note: '删没有记录指向的文件' },
      { method: 'GET', path: '/v2/diagnostics/extractions', note: '最近 50 条失败上报' },
    ],
    missing: '清理没有 dry-run（只有执行前的说明与二次确认）；上报列表只有 50 条、不能按平台筛；不能手动改配额（配额在服务端配置里）。',
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
        管理台按 5c–5f 分批接入：标「可用」的是已经接了真实接口的页面，其余仍是占位页，
        每页都写了"还差什么"，不会出现"点进去空白但看起来已完成"的情况。
      </p>
      <ul class="plain">
        {SECTIONS.map((s) => (
          <li>
            <Link to={s.path}>{s.label}</Link>
            <span class="tag">{s.phase}</span>
            {s.done ? <span class="tag ok">可用</span> : null}
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
