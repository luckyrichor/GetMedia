/** 极小的路径路由：owner 页面挂在 /getmedia/owner/ 下，nginx 对未知路径回退到 owner 的 index.html。 */
import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';

export const BASE = (import.meta.env.BASE_URL ?? '/').replace(/\/$/, ''); // "/getmedia/owner"

export function currentPath(loc: Pick<Location, 'pathname'> = location): string {
  let p = loc.pathname;
  if (BASE && p.startsWith(BASE)) p = p.slice(BASE.length);
  p = p.replace(/\/+$/, '');
  return p === '' ? '/' : p;
}

export function href(to: string): string {
  return BASE + (to === '/' ? '/' : to);
}

const EVENT = 'gmo:navigate';

export function navigate(to: string, replace = false): void {
  const url = href(to);
  if (replace) history.replaceState(null, '', url);
  else history.pushState(null, '', url);
  window.dispatchEvent(new Event(EVENT));
  window.scrollTo(0, 0);
}

export function useRoute(): string {
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
  return currentPath();
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
