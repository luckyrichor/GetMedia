import { useState } from 'preact/hooks';
import { api } from '@shared/api';
import { clientInfo } from '@shared/clientinfo';
import { formatBytes, shortId } from '@shared/format';
import { Alert, Button, Check, Page } from '../ui';
import { useSession } from '../session';

export const MAX_TEXT = 2000;
export const MAX_CONTACT = 200;
export const MAX_IMAGES = 3;
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
export const MAX_TOTAL_BYTES = 12 * 1024 * 1024;
export const IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp'];

const CATEGORIES: [string, string][] = [
  ['bug', '功能问题'],
  ['suggestion', '建议'],
  ['account', '账号相关'],
  ['other', '其他'],
];

/** 客户端提前检查，给出明确提示；服务端仍会重新校验并重新编码图片。 */
export function imageProblem(files: File[]): string | null {
  if (files.length > MAX_IMAGES) return `最多添加 ${MAX_IMAGES} 张图片`;
  let total = 0;
  for (const f of files) {
    if (!IMAGE_TYPES.includes(f.type)) return '只支持 JPEG、PNG、WebP 图片';
    if (f.size > MAX_IMAGE_BYTES) return `单张图片不能超过 ${formatBytes(MAX_IMAGE_BYTES)}`;
    total += f.size;
  }
  if (total > MAX_TOTAL_BYTES) return `图片合计不能超过 ${formatBytes(MAX_TOTAL_BYTES)}`;
  return null;
}

const newKey = () => crypto.randomUUID();

export function Contact() {
  const s = useSession();
  const [category, setCategory] = useState('bug');
  const [text, setText] = useState('');
  const [contact, setContact] = useState('');
  const [files, setFiles] = useState<File[]>([]);
  const [withDiag, setWithDiag] = useState(true);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [sent, setSent] = useState<string | null>(null);
  // 同一份内容重试时沿用同一个幂等键：第一次其实已送达只是响应丢了，也不会重复入库
  const [key, setKey] = useState(newKey);

  const imgErr = imageProblem(files);

  async function submit(e: Event) {
    e.preventDefault();
    setErr(null);
    if (!text.trim()) return setErr('请填写要反馈的内容');
    if (imgErr) return setErr(imgErr);
    const form = new FormData();
    form.set('text', text.trim());
    form.set('category', category);
    if (contact.trim()) form.set('contact', contact.trim());
    form.set('idempotency_key', key);
    if (withDiag) {
      const c = clientInfo();
      form.set('diagnostics', JSON.stringify({ app_version: c.app_version, os: c.os, device: c.device, channel: c.channel, locale: navigator.language }));
    }
    for (const f of files) form.append('images', f, f.name);
    setBusy(true);
    try {
      const r = await api.feedback(form);
      setSent(r.id);
      setText('');
      setContact('');
      setFiles([]);
      setKey(newKey());
    } catch (ex) {
      setErr(s.fail(ex));
    } finally {
      setBusy(false);
    }
  }

  if (sent) {
    return (
      <Page title="联系作者">
        <Alert kind="ok">已收到你的反馈，编号 <code data-testid="feedback-id">{shortId(sent)}</code>。谢谢！</Alert>
        <Button variant="secondary" onClick={() => setSent(null)}>再写一条</Button>
      </Page>
    );
  }

  return (
    <Page title="联系作者" lead="遇到问题、有建议，或想提出账号数据的更正、删除请求，都可以在这里写。">
      <form onSubmit={submit} noValidate>
        <div class="field">
          <label for="fb-cat">类别</label>
          <select id="fb-cat" value={category} onChange={(e) => setCategory((e.currentTarget as HTMLSelectElement).value)}>
            {CATEGORIES.map(([v, l]) => <option value={v} key={v}>{l}</option>)}
          </select>
        </div>
        <div class="field">
          <label for="fb-text">内容</label>
          <textarea id="fb-text" rows={7} maxLength={MAX_TEXT} value={text} required
            onInput={(e) => setText((e.currentTarget as HTMLTextAreaElement).value)} />
          <p class="hint" data-testid="counter">{[...text].length} / {MAX_TEXT}</p>
        </div>
        <div class="field">
          <label for="fb-contact">联系方式（选填）</label>
          <input id="fb-contact" type="text" maxLength={MAX_CONTACT} value={contact} autoComplete="off"
            onInput={(e) => setContact((e.currentTarget as HTMLInputElement).value)} />
          <p class="hint">只会写在反馈正文里，不会被当作已验证的联系方式；不想留可以不填。</p>
        </div>
        <div class="field">
          <label for="fb-img">图片（选填，最多 {MAX_IMAGES} 张）</label>
          <input id="fb-img" type="file" accept={IMAGE_TYPES.join(',')} multiple
            onChange={(e) => setFiles(Array.from((e.currentTarget as HTMLInputElement).files ?? []))} />
          <p class="hint">JPEG、PNG、WebP，单张 ≤ 5 MB，合计 ≤ 12 MB。图片会在服务器上重新编码并去除位置等元数据。</p>
          {files.length ? (
            <ul class="files">
              {files.map((f, i) => (
                <li key={`${f.name}-${i}`}>
                  <span>{f.name} <small class="muted">（{formatBytes(f.size)}）</small></span>
                  <button type="button" class="btn btn-link" onClick={() => setFiles(files.filter((_, j) => j !== i))}>移除</button>
                </li>
              ))}
            </ul>
          ) : null}
          {imgErr ? <p class="field-error">{imgErr}</p> : null}
        </div>
        <Check checked={withDiag} onChange={setWithDiag}>附带诊断信息（网页版本、系统、浏览器大类、语言），帮助定位问题</Check>
        {err ? <Alert>{err}</Alert> : null}
        <Button type="submit" busy={busy} disabled={Boolean(imgErr)}>提交</Button>
      </form>
    </Page>
  );
}
