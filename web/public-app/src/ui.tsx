import type { ButtonHTMLAttributes, ComponentChildren } from 'preact';
import { useEffect, useId, useRef, useState } from 'preact/hooks';

/** 页面标题：同时设置 <title>，并把焦点放到 h1（切换页面时读屏用户能知道已跳转）。 */
export function Page({ title, children, lead }: { title: string; children: ComponentChildren; lead?: ComponentChildren }) {
  const h = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    document.title = `${title} · GetMedia`;
    h.current?.focus({ preventScroll: true });
  }, [title]);
  return (
    <section class="page">
      <h1 ref={h} tabIndex={-1}>{title}</h1>
      {lead ? <p class="lead">{lead}</p> : null}
      {children}
    </section>
  );
}

export function Alert({ kind = 'error', children }: { kind?: 'error' | 'warn' | 'ok' | 'info'; children: ComponentChildren }) {
  return (
    <div class={`alert alert-${kind}`} role={kind === 'error' ? 'alert' : 'status'}>
      {children}
    </div>
  );
}

export function Spinner({ label = '加载中…' }: { label?: string }) {
  return <p class="muted" role="status">{label}</p>;
}

type Btn = ButtonHTMLAttributes<HTMLButtonElement> & { busy?: boolean; variant?: 'primary' | 'secondary' | 'danger' };
export function Button({ busy, variant = 'primary', children, disabled, ...rest }: Btn) {
  return (
    <button class={`btn btn-${variant}`} disabled={disabled || busy} aria-busy={busy ? 'true' : undefined} {...rest}>
      {busy ? '请稍候…' : children}
    </button>
  );
}

interface FieldProps {
  label: string;
  value: string;
  onInput: (v: string) => void;
  type?: 'text' | 'password';
  hint?: ComponentChildren;
  error?: string | null;
  autoComplete?: string;
  maxLength?: number;
  required?: boolean;
  name?: string;
}

/** 眼睛图标：睁眼表示"点一下显示"，闭眼表示"点一下遮住"。纯图标，无文字。 */
function EyeIcon() {
  return (
    <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"
         stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">
      <path d="M1.5 12S5.5 5 12 5s10.5 7 10.5 7-4 7-10.5 7S1.5 12 1.5 12z" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  );
}

function EyeOffIcon() {
  return (
    <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"
         stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">
      <path d="M1.5 12S5.5 5 12 5s10.5 7 10.5 7-4 7-10.5 7S1.5 12 1.5 12z" />
      <circle cx="12" cy="12" r="3" />
      <path d="M3 3l18 18" />
    </svg>
  );
}

export function Field({ label, value, onInput, type = 'text', hint, error, autoComplete, maxLength, required, name }: FieldProps) {
  const id = useId();
  const [shown, setShown] = useState(false);
  const isPw = type === 'password';
  const common = {
    id,
    name,
    value,
    onInput: (e: Event) => onInput((e.currentTarget as HTMLInputElement).value),
    autoComplete,
    maxLength,
    required,
    autoCapitalize: 'none' as const,
    autoCorrect: 'off' as const,
    spellcheck: false,
    'aria-invalid': error ? ('true' as const) : undefined,
    'aria-describedby': hint || error ? `${id}-d` : undefined,
  };
  return (
    <div class="field">
      <label for={id}>{label}</label>
      <div class={isPw ? 'pw-row' : undefined}>
        {isPw && !shown ? <input {...common} type="password" /> : <input {...common} type="text" />}
        {isPw ? (
          <button
            type="button"
            class="btn btn-link pw-eye"
            onClick={() => setShown((v) => !v)}
            aria-pressed={shown}
            aria-label={shown ? '隐藏密码' : '显示密码'}
            title={shown ? '隐藏密码' : '显示密码'}
          >
            {shown ? <EyeOffIcon /> : <EyeIcon />}
          </button>
        ) : null}
      </div>
      {hint || error ? <p id={`${id}-d`} class={error ? 'field-error' : 'hint'}>{error || hint}</p> : null}
    </div>
  );
}

export function Check({ checked, onChange, children }: { checked: boolean; onChange: (v: boolean) => void; children: ComponentChildren }) {
  const id = useId();
  return (
    <div class="check">
      <input id={id} type="checkbox" checked={checked} onChange={(e) => onChange((e.currentTarget as HTMLInputElement).checked)} />
      <label for={id}>{children}</label>
    </div>
  );
}

export function CopyButton({ text, label = '复制' }: { text: string; label?: string }) {
  const [state, setState] = useState<'idle' | 'ok' | 'fail'>('idle');
  return (
    <button
      type="button"
      class="btn btn-secondary"
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(text);
          setState('ok');
        } catch {
          setState('fail');
        }
        setTimeout(() => setState('idle'), 2500);
      }}
    >
      {state === 'ok' ? '已复制' : state === 'fail' ? '复制失败，请手动选择' : label}
    </button>
  );
}
