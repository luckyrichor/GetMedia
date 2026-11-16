import { Account } from './pages/account';
import { Login, Recover, Register } from './pages/auth';
import { Contact } from './pages/contact';
import { Home, Legal, NotFound } from './pages/content';
import { RequireAuth } from './pages/gates';
import { Link, navigate, useRoute } from './router';
import { SessionProvider, useSession } from './session';
import { Alert } from './ui';

function Header({ path }: { path: string }) {
  const s = useSession();
  const authed = s.state.status === 'authed';
  const cur = (p: string) => (path === p ? ('page' as const) : undefined);
  return (
    <header class="top">
      <Link to="/" class="brand">GetMedia</Link>
      <nav aria-label="主导航">
        <Link to="/contact" aria-current={cur('/contact')}>联系作者</Link>
        {authed ? (
          <>
            <Link to="/account" aria-current={cur('/account')}>账号</Link>
            <button type="button" class="btn btn-link" onClick={() => void s.logout().then(() => navigate('/', true))}>退出</button>
          </>
        ) : (
          <>
            <Link to="/login" aria-current={cur('/login')}>登录</Link>
            <Link to="/register" aria-current={cur('/register')}>注册</Link>
          </>
        )}
      </nav>
    </header>
  );
}

function Banners() {
  const s = useSession();
  return (
    <>
      {s.config?.maintenance ? <Alert kind="warn"><strong>维护提示：</strong>{s.config.maintenance}</Alert> : null}
      {s.configError ? <Alert kind="warn">暂时无法连接服务器，部分内容可能无法显示。</Alert> : null}
    </>
  );
}

function Routes({ path }: { path: string }) {
  switch (path) {
    case '/': return <Home />;
    case '/register': return <Register />;
    case '/login': return <Login />;
    case '/recover': return <Recover />;
    case '/account': return <RequireAuth path="/account"><Account /></RequireAuth>;
    case '/contact': return <RequireAuth path="/contact"><Contact /></RequireAuth>;
    case '/legal/user-agreement': return <Legal doc="user-agreement" />;
    case '/legal/privacy-policy': return <Legal doc="privacy-policy" />;
    default: return <NotFound />;
  }
}

function Shell() {
  const { path } = useRoute();
  return (
    <>
      <Header path={path} />
      <main>
        <Banners />
        <Routes path={path} />
      </main>
      <footer class="foot">
        <Link to="/legal/user-agreement">用户协议</Link> · <Link to="/legal/privacy-policy">隐私说明</Link>
      </footer>
    </>
  );
}

export function App() {
  return (
    <SessionProvider>
      <Shell />
    </SessionProvider>
  );
}
