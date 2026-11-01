/** 极小的路径路由。网页挂在 /getmedia/ 下（Vite base）；nginx 对未知路径回退到 index.html。 */
import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';

const BASE = (import.meta.env.BASE_URL ?? '/').replace(/\/$/, ''); // 例如 "/getmedia"

/** 去掉前缀与结尾斜杠后的路径，始终以 / 开头。 */
export function currentPath(loc: Pick<Location, 'pathname'> = location): string {
  let p = loc.pathname;
  if (BASE && p.startsWith(BASE)) p = p.slice(BASE.length);
  p = p.replace(/\/+$/, '');
  return p === '' ? '/' : p;
}

export function href(to: string): string {
  return BASE + (to === '/' ? '/' : to);
}

const EVENT = 'gm:navigate';

export function navigate(to: string, replace = false): void {
  const url = href(to);
  if (replace) history.replaceState(null, '', url);
  else history.pushState(null, '', url);
  window.dispatchEvent(new Event(EVENT));
  window.scrollTo(0, 0);
}

/** 只允许站内的、形如 /xxx 或 /xxx/yyy 的返回路径，防止被构造成跳到别处。 */
export function safeNext(raw: string | null | undefined): string | null {
  return raw && /^\/[a-z][a-z0-9/-]*$/.test(raw) ? raw : null;
}

export function useRoute(): { path: string; search: URLSearchParams } {
  const [, force] = useState(0);
  useEffect(() => {
    const on = () => force((n) => n + 1);
    window.addEventListener(EVENT, on);
    window.addEventListener('popstate', on);
    return () => {
      window.removeEventListener(EVENT, on);
      window.removeEventListener('popstate', on);
    };
  }, []);
  return { path: currentPath(), search: new URLSearchParams(location.search) };
}

export function Link({ to, children, class: cls, ...rest }: { to: string; children: ComponentChildren; class?: string; 'aria-current'?: 'page' | undefined }) {
  return (
    <a
      href={href(to)}
      class={cls}
      {...rest}
      onClick={(e) => {
        if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
        e.preventDefault();
        navigate(to);
      }}
    >
      {children}
    </a>
  );
}
