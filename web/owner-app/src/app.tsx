/** owner 认证壳：状态门（未登录 / 无权 / 已授权）+ 导航骨架。
 *
 * 门怎么判：只调 `GET /v2/config/owner`。
 *   404 → 边缘把"匿名"和"非 owner"都回成 404（对外不区分，也不承认管理台存在）：显示中性提示，不跳登录；
 *   401 → 未登录（只有没经过 nginx 时才可能出现，例如本地 dev）：给登录入口，页面不自动跳转；
 *   403 → 不是 owner（同上，应用自己回的状态）：说明原因，不把人绕回登录页；
 *   200 → 已授权：再取一次 `/account/me` 显示身份。
 * 边缘（nginx auth_request）与应用（require_tier）各自还有一层校验，这里只是第三层"体验"。
 */
import type { ComponentChildren } from 'preact';
import type { JSX } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { isApiError, messageFor } from '@shared/errors';
import { isAdmin } from '@shared/api';
import { LOGIN_URL, PUBLIC_BASE, ownerApi, type Me, type OwnerConfig } from './api';
import { Link, navigate, useRoute } from './router';
import { Overview, Placeholder, sectionFor, visibleSections } from './sections';
import { FeedbackPage } from './pages/feedback';
import { LibraryPage } from './pages/library';
import { ExtractPage } from './pages/extract';
import { CreatorsPage } from './pages/creators';
import { SchedulesPage } from './pages/schedules';
import { AgentPage } from './pages/agent';
import { PlatformsPage } from './pages/platforms';
import { MaintenancePage } from './pages/maintenance';
import { AiPage } from './pages/ai';
import { AccountsPage } from './pages/accounts';
import { InvitesPage } from './pages/invites';

/** 栏目路径 → 真实页面。十一个栏目都已接完；漏注册的栏目会落到 `Placeholder`（列出接口与"还差什么"）。
 *  `/accounts` 与 `/invites` 只有 admin 能看到导航，但页面本身在非 admin 直接输 URL 时也会自己拦一道。 */
const PAGES: Record<string, () => JSX.Element> = {
  '/accounts': AccountsPage,
  '/invites': InvitesPage,
  '/feedback': FeedbackPage,
  '/library': LibraryPage,
  '/extract': ExtractPage,
  '/creators': CreatorsPage,
  '/schedules': SchedulesPage,
  '/agent': AgentPage,
  '/platforms': PlatformsPage,
  '/maintenance': MaintenancePage,
  '/ai': AiPage,
};

type Gate =
  | { k: 'loading' }
  | { k: 'login' }
  | { k: 'hidden' }
  | { k: 'forbidden' }
  | { k: 'error'; message: string }
  | { k: 'ready'; cfg: OwnerConfig; me: Me };

function useGate(): { gate: Gate; retry: () => void } {
  const [gate, setGate] = useState<Gate>({ k: 'loading' });
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let alive = true;
    setGate({ k: 'loading' });
    (async () => {
      try {
        const cfg = await ownerApi.config();
        const me = await ownerApi.me();
        if (alive) setGate({ k: 'ready', cfg, me });
      } catch (e) {
        if (!alive) return;
        if (isApiError(e) && e.status === 404) setGate({ k: 'hidden' });
        else if (isApiError(e) && e.status === 401) setGate({ k: 'login' });
        else if (isApiError(e) && e.status === 403) setGate({ k: 'forbidden' });
        else setGate({ k: 'error', message: messageFor(e) });
      }
    })();
    return () => {
      alive = false;
    };
  }, [tick]);

  return { gate, retry: () => setTick((n) => n + 1) };
}

function Center({ children }: { children: ComponentChildren }) {
  return (
    <main class="center">
      <div class="panel">{children}</div>
    </main>
  );
}

/** 未登录：只给入口，不自动跳转（跳转是"这里需要登录"的信号，不需要给扫描器）。
 *  正常流程是先在公开站 /getmedia/login 登录，登录成功后由公开站读 tier、owner 才引导进 /owner/。
 */
function LoginNeeded() {
  return (
    <Center>
      <h1>需要先登录</h1>
      <p class="lead">管理台只对完整版与管理员账号开放。请先登录，登录后会按账号权限决定能不能进来。</p>
      <p>
        <a class="btn" href={LOGIN_URL}>去登录</a>
      </p>
      <p class="small muted">
        登录后仍回到这一页，通常是浏览器禁用了 Cookie —— 管理台依赖 HttpOnly 会话 Cookie，
        没有它无法保持登录。
      </p>
      <p class="small"><Link to={PUBLIC_BASE}>返回公开站</Link></p>
    </Center>
  );
}

/** 404：边缘对"匿名"和"非 owner"都给这个状态，对外不区分，所以文案也不能替它区分。 */
function Hidden() {
  return (
    <Center>
      <h1>页面不存在</h1>
      <p class="lead">这个地址下没有内容。</p>
      <p class="small muted">如果你是从书签或历史记录进来的，可能是旧链接。</p>
      <p>
        <a class="btn btn-ghost" href={PUBLIC_BASE}>返回公开站</a>
      </p>
    </Center>
  );
}

function Forbidden() {
  return (
    <Center>
      <h1>当前账号没有完整版权限</h1>
      <p class="lead">这个账号可以正常使用手机应用与公开网页，但管理台只对完整版与管理员开放。</p>
      <p class="small muted">
        如果你认为这是误判：确认登录的是正确的账号（右上角没有切换账号的入口，需要先在公开站退出再登录）。
      </p>
      <p>
        <a class="btn" href={`${PUBLIC_BASE}account`}>查看当前账号</a>{' '}
        <a class="btn btn-ghost" href={PUBLIC_BASE}>返回公开站</a>
      </p>
    </Center>
  );
}

function Failed({ message, retry }: { message: string; retry: () => void }) {
  return (
    <Center>
      <h1>管理台暂时打不开</h1>
      <div class="alert warn">{message}</div>
      <p class="small muted">
        常见原因：API 未启动、nginx owner 通道未配置、或服务器正在维护。重试不会改变任何数据。
      </p>
      <p>
        <button type="button" class="btn" onClick={retry}>重试</button>{' '}
        <a class="btn btn-ghost" href={PUBLIC_BASE}>返回公开站</a>
      </p>
    </Center>
  );
}

function Header({ me }: { me: Me }) {
  const [busy, setBusy] = useState(false);
  async function logout() {
    setBusy(true);
    try {
      await ownerApi.logout();
    } catch {
      /* 会话已失效也算退出成功 */
    } finally {
      location.replace(PUBLIC_BASE);
    }
  }
  return (
    <header class="top">
      <div class="brand">
        GetMedia <span class="tag owner">管理台</span>
      </div>
      <div class="who">
        {/* 只有管理员加绿色标识；免费版/完整版不加任何标签，免得把"有效期"之类的事混进页头 */}
        {isAdmin(me.tier) ? <span class="tag admin">管理员</span> : null}
        <span class="muted small">{me.login_name}</span>
        {/* 退出是低频动作，用低调的文字按钮：不跟页面里的主按钮抢注意力 */}
        <button type="button" class="text-btn muted" disabled={busy} onClick={() => void logout()}>
          退出
        </button>
      </div>
    </header>
  );
}

function Nav({ path, tier }: { path: string; tier: string }) {
  const items = [{ path: '/', label: '概览' }, ...visibleSections(tier).map((s) => ({ path: s.path, label: s.label }))];
  return (
    <nav class="side" aria-label="管理台导航">
      {items.map((it) => (
        <Link to={it.path} aria-current={path === it.path ? 'page' : undefined}>
          <span>{it.label}</span>
        </Link>
      ))}
    </nav>
  );
}

/** 内容区的宽度档：默认 62rem；列多的两页放宽到 84rem；批量提取不设上限。 */
const WIDE: Record<string, string> = {
  '/accounts': 'content content-wide',
  '/invites': 'content content-wide',
  '/creators': 'content content-full',
};

function Shell({ cfg, me }: { cfg: OwnerConfig; me: Me }) {
  const path = useRoute();
  const section = sectionFor(path);
  const Page = PAGES[path];
  const body =
    path === '/' ? <Overview cfg={cfg} tier={me.tier} /> : Page ? <Page /> : section ? <Placeholder section={section} /> : <Missing />;
  return (
    <>
      <Header me={me} />
      <div class="layout">
        <Nav path={path} tier={me.tier} />
        {/* 权限管理、邀请码两页列多，宽屏上按默认 62rem 会右边空一大片，单独放宽；
            批量提取的表格要铺满，干脆不设上限 */}
        <main class={WIDE[path] ?? 'content'}>{body}</main>
      </div>
    </>
  );
}

function Missing() {
  const path = useRoute();
  return (
    <Center>
      <h1>页面不存在</h1>
      <p class="lead">
        <code>{path}</code> 不在管理台的栏目表里。
      </p>
      <p><Link to="/">回到概览</Link></p>
    </Center>
  );
}

export function App() {
  const { gate, retry } = useGate();
  useEffect(() => {
    // 从栏目页直接刷新时，路由已经在 URL 里；这里只保证首屏不滚到中间
    window.scrollTo(0, 0);
  }, []);

  switch (gate.k) {
    case 'loading':
      return (
        <Center>
          <p class="muted">正在确认身份…</p>
        </Center>
      );
    case 'login':
      return <LoginNeeded />;
    case 'hidden':
      return <Hidden />;
    case 'forbidden':
      return <Forbidden />;
    case 'error':
      return <Failed message={gate.message} retry={retry} />;
    case 'ready':
      return <Shell cfg={gate.cfg} me={gate.me} />;
  }
}

// 供 5c 起的页面复用：401/403 时把壳切回对应状态（现在只导出，避免各页各写一套）
export function goLogin(): void {
  location.replace(LOGIN_URL);
}
export function goHome(): void {
  navigate('/');
}
