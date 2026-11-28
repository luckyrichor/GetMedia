/**
 * 「切换页面要不要干等」实测：桩接口人为加 400ms 延迟（模拟跨境往返），
 * 量三个列表页从点击导航到内容出现的时间。
 *
 * 用 Playwright 缓存里的 chromium-headless-shell（**不启动系统安装的 Chrome**）。
 *
 *   node scripts/perf-switch.cjs
 */
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const puppeteer = require('/Users/lc/Project/GetMedia/web/node_modules/puppeteer-core');

const DIST = '/Users/lc/Project/GetMedia/web/owner-app/dist';
const EXE = '/Users/lc/Library/Caches/ms-playwright/chromium_headless_shell-1234/chrome-headless-shell-mac-arm64/chrome-headless-shell';
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };
/** 模拟跨境往返：服务器在境外，一个来回就是这个量级 */
const RTT = 400;

function serve() {
  return new Promise((resolve) => {
    const srv = http.createServer((req, res) => {
      let p = new URL(req.url, 'http://x').pathname.replace(/^\/getmedia\/owner/, '') || '/';
      if (p === '/' || !path.extname(p)) p = '/index.html';
      const file = path.join(DIST, p);
      if (!fs.existsSync(file)) { res.writeHead(404); res.end('nf'); return; }
      res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
      fs.createReadStream(file).pipe(res);
    });
    srv.listen(0, '127.0.0.1', () => resolve(srv));
  });
}

const now = Math.floor(Date.now() / 1000);
const track = (id) => ({
  id, media_type: 'audio', platform: 'bilibili', video_id: `BV${id}`, page: 1,
  title: `曲目 ${id}`, uploader: `UP ${id}`, duration_sec: 200, cover_url: null,
  source_url: 'https://e.invalid/v', storage: 'archive', bytes: 1048576, pinned: false,
  deleted: false, updated_seq: id, created_at: now - id * 60, updated_at: now - id * 60,
  cache_expires_at: null, has_file: true,
});
const job = (id) => ({
  id, status: 'done', url: `https://e.invalid/${id}`, platform: 'bilibili', media_type: 'audio',
  storage: 'archive', format: 'mp3', title: `任务 ${id}`, uploader: `UP ${id}`,
  created_at: now - id * 60, updated_at: now - id * 60, error: null, track_id: id,
  nickname: `UP ${id}`, duration_sec: 200, bytes: 1048576, progress: 100, total: 1, done: 1,
});
const creatorJob = (id) => ({
  id, status: 'done', url: `https://e.invalid/c${id}`, platform: 'bilibili',
  mode: 'all', media_type: 'audio', storage: 'archive', formats: ['mp3'],
  nickname: `UP ${id}`, title: `批量任务 ${id}`, total: 10, done: 10, failed: 0,
  created_at: now - id * 60, updated_at: now - id * 60, error: null, progress: 100,
});

const CONFIG = {
  cache_ttl_seconds: 86400, cache_quota_bytes: 5 * 1024 ** 3, archive_quota_bytes: 20 * 1024 ** 3,
  creator: { max_items: 200, max_start: 60 }, credential_vault: true,
};
const ME = { id: 'acct_1', login_name: 'owner1', tier: 'full', tier_expires_at: null, consent_required: false, recovery_confirmed: true, status: 'active' };

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  const srv = await serve();
  const base = `http://127.0.0.1:${srv.address().port}/getmedia/owner/`;
  const browser = await puppeteer.launch({
    executablePath: EXE, headless: true, args: ['--no-sandbox'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 900 });
  await page.setRequestInterception(true);

  page.on('request', async (req) => {
    const u = new URL(req.url());
    if (!u.pathname.startsWith('/getmedia/owner/api/')) return req.continue();
    const p = u.pathname.replace('/getmedia/owner/api', '');
    const json = (o) => req.respond({ status: 200, contentType: 'application/json', body: JSON.stringify(o) });
    if (p === '/v2/config/owner') { await sleep(150); return json(CONFIG); }
    if (p === '/account/me') { await sleep(150); return json(ME); }
    // 三个列表接口：都按一次跨境往返延迟
    if (p === '/v2/tracks') { await sleep(RTT); return json({ items: Array.from({ length: 36 }, (_, i) => track(i + 1)), count: 36 }); }
    if (p === '/v2/extraction-jobs') { await sleep(RTT); return json({ jobs: Array.from({ length: 20 }, (_, i) => job(i + 1)) }); }
    if (p === '/v2/creators/jobs') { await sleep(RTT); return json({ jobs: Array.from({ length: 7 }, (_, i) => creatorJob(i + 1)) }); }
    return json({});
  });

  const clickNav = async (label) => {
    const ok = await page.evaluate((label) => {
      const el = Array.from(document.querySelectorAll('.nav-item'))
        .find((a) => a.textContent.trim().startsWith(label));
      if (!el) return false;
      el.click();
      return true;
    }, label);
    if (!ok) throw new Error(`点不到导航「${label}」`);
  };

  /** 从点导航到目标元素出现，量到毫秒 */
  const measure = async (label, navText, selector) => {
    const t0 = Date.now();
    await clickNav(navText);
    await page.waitForSelector(selector, { timeout: 8000 });
    const ms = Date.now() - t0;
    console.log(`  ${label}：${ms} ms`);
    return ms;
  };

  console.log(`桩接口延迟 = ${RTT} ms（模拟一次跨境往返）\n`);

  // 冷启动：直接落在媒体库（缓存是空的，这一次总得等一个来回）
  let t0 = Date.now();
  await page.goto(base + 'library', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.track', { timeout: 15000 });
  console.log(`冷启动直接进媒体库（无缓存，含 gate 两个请求）：${Date.now() - t0} ms\n`);

  console.log('切走再切回（有缓存，应接近 0）：');
  await clickNav('概览'); await sleep(300);
  await measure('媒体库', '媒体库', '.track');
  await clickNav('概览'); await sleep(300);
  await measure('单个提取', '单个提取', 'tr[data-job]');
  await clickNav('概览'); await sleep(300);
  await measure('批量提取', '批量提取', 'tr[data-job]');

  console.log('\n连续再切一轮：');
  await measure('媒体库', '媒体库', '.track');
  await measure('单个提取', '单个提取', 'tr[data-job]');
  await measure('批量提取', '批量提取', 'tr[data-job]');

  // 刷新：内存缓存没了，但 sessionStorage 那份还在 —— 列表不该再等一个往返
  console.log('\n刷新页面（内存清空、sessionStorage 还在）：');
  await clickNav('媒体库'); await sleep(200);
  t0 = Date.now();
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.track', { timeout: 15000 });
  console.log(`  刷新后媒体库首屏（含 gate）：${Date.now() - t0} ms`);
  const fromSs = await page.evaluate(() => Object.keys(sessionStorage).filter((k) => k.startsWith('getmedia.cache.')));
  console.log('  sessionStorage 里的缓存键 =', fromSs.join(', ') || '(空)');

  await browser.close();
  srv.close();
}

main().catch((e) => { console.error(e); process.exit(1); });
