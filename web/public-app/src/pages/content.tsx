import { useEffect } from 'preact/hooks';
import { api } from '@shared/api';
import { messageFor } from '@shared/errors';
import { Markdown } from '@shared/markdown';
import { useAsync } from '../hooks';
import { Link } from '../router';
import { useSession } from '../session';
import { Alert, Button, Page, Spinner } from '../ui';

export function Home() {
  const s = useSession();
  const cfg = s.config;
  const authed = s.state.status === 'authed';
  const setFlash = s.setFlash;
  useEffect(() => () => setFlash(null), [setFlash]);
  const latest = cfg?.latest ?? null;

  return (
    <Page title="GetMedia" lead="把你有权访问的公开视频和音频，提取、保存、整理并播放在自己的手机上。">
      {s.flash ? <Alert kind={s.flash.kind}>{s.flash.text}</Alert> : null}

      <div class="card">
        <h2>它是什么</h2>
        <ul>
          <li>手机应用：提取 B 站、抖音的公开作品，保存音频或视频，并在本地整理、剪辑和播放。</li>
          <li>大部分功能在你的手机本地运行，你的媒体文件不会上传到服务器。</li>
          <li>需要注册账号才能使用；账号用于登录、管理设备和联系作者。</li>
        </ul>
        <p class="muted">这个网页只用于下载安装包、注册和管理账号、联系作者，<strong>不能在网页里提取或播放</strong>。</p>
      </div>

      <div class="card" id="download">
        <h2>下载手机应用（Android）</h2>
        {cfg === null && !s.configError ? <Spinner /> : null}
        {s.configError ? <Alert kind="warn">暂时无法获取最新版本信息，请稍后刷新重试。</Alert> : null}
        {cfg !== null && !latest ? <p class="muted">安装包暂未发布。</p> : null}
        {latest ? (
          <>
            <p>
              最新版本 <strong data-testid="latest-version">{latest.version}</strong>
              {latest.notes ? <><br /><span class="muted">{latest.notes}</span></> : null}
            </p>
            {latest.apk ? (
              <p>
                <a class="btn btn-primary" href={latest.apk} rel="noopener noreferrer" data-testid="apk-link">下载安装包</a>
              </p>
            ) : (
              <p class="muted">下载地址暂未提供。</p>
            )}
            {latest.sha256 ? (
              <p class="small muted">
                SHA-256（下载后可核对文件是否完整）：<br />
                <code class="wrap" data-testid="apk-sha">{latest.sha256}</code>
              </p>
            ) : null}
          </>
        ) : null}
        <p class="small muted">安装时系统可能提示“允许安装未知来源应用”，这是安装不经应用商店分发的应用时的正常提示。</p>
      </div>

      <div class="card">
        <h2>账号</h2>
        {authed ? (
          <p><Link class="btn btn-primary" to="/account">账号与安全</Link> <Link class="btn btn-secondary" to="/contact">联系作者</Link></p>
        ) : (
          <>
            {cfg && !cfg.registration_open ? <p class="muted">目前未开放自由注册；持有邀请码仍可注册。</p> : null}
            <p><Link class="btn btn-primary" to="/register">注册</Link> <Link class="btn btn-secondary" to="/login">登录</Link></p>
          </>
        )}
      </div>
    </Page>
  );
}

const DOCS = {
  'user-agreement': { key: 'user_agreement', title: '用户协议' },
  'privacy-policy': { key: 'privacy_policy', title: '隐私说明' },
} as const;

export function Legal({ doc }: { doc: keyof typeof DOCS }) {
  const meta = DOCS[doc];
  const legal = useAsync(() => api.legal(), []);
  const d = legal.data?.[meta.key];
  // 文档首行是 "# GetMedia 用户协议"，与页面标题重复，去掉
  const body = d ? d.text.replace(/^\s*#\s+[^\n]*\n+/, '') : '';
  return (
    <Page title={meta.title}>
      {legal.loading && !d ? <Spinner /> : null}
      {legal.error ? (
        <Alert>
          无法加载{meta.title}：{messageFor(legal.error)} <Button variant="secondary" onClick={legal.reload}>重试</Button>
        </Alert>
      ) : null}
      {d ? (
        <>
          <p class="muted small" data-testid="legal-meta">版本 v{d.version} · {d.effective_date} 生效</p>
          <Markdown text={body} headingOffset={0} />  {/* 已去掉文档自带的 # 标题；## 小节直接作为 h2，紧接页面 h1，不跳级 */}
          <p class="muted small">
            另见：<Link to={doc === 'user-agreement' ? '/legal/privacy-policy' : '/legal/user-agreement'}>
              {doc === 'user-agreement' ? '隐私说明' : '用户协议'}
            </Link>
          </p>
        </>
      ) : null}
    </Page>
  );
}

export function NotFound() {
  return (
    <Page title="页面不存在">
      <p><Link to="/">回到首页</Link></p>
    </Page>
  );
}
