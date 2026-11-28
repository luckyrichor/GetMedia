/**
 * 媒体库改版自查截图：本地起静态服务 + 拦截接口塞桩数据，用 Playwright 缓存里的
 * chromium-headless-shell 跑（**不启动系统安装的 Chrome**）。
 *
 * 产出：local-artifacts/library-ui/*.png
 */
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const puppeteer = require('/Users/lc/Project/GetMedia/web/node_modules/puppeteer-core');

const DIST = '/Users/lc/Project/GetMedia/web/owner-app/dist';
const OUT = '/Users/lc/Project/GetMedia/local-artifacts/library-ui';
const EXE = '/Users/lc/Library/Caches/ms-playwright/chromium_headless_shell-1234/chrome-headless-shell-mac-arm64/chrome-headless-shell';

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };

function serve() {
  return new Promise((resolve) => {
    const srv = http.createServer((req, res) => {
      const u = new URL(req.url, 'http://x');
      let p = u.pathname.replace(/^\/getmedia\/owner/, '') || '/';
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
const T = (id, over = {}) => ({
  id,
  media_type: 'audio',
  platform: ['bilibili', 'douyin', 'local'][id % 3],
  video_id: `BV1xx${id}`,
  page: 1,
  title: `曲目 ${id}`,
  uploader: id % 4 === 0 ? null : `UP 主 ${id}`,
  duration_sec: 120 + id * 7,
  cover_url: null,
  source_url: 'https://example.invalid/v',
  storage: 'archive',
  bytes: (id % 5 + 1) * 1024 * 1024,
  pinned: false,
  deleted: false,
  updated_seq: id,
  created_at: now - id * 3600,
  updated_at: now - id * 3600,
  cache_expires_at: null,
  has_file: true,
  ...over,
});

const ITEMS = [
  T(1, { title: '【4K 修复】这是一个特别特别长的视频标题，用来验证两行截断和悬停看全文的效果，后面还有一堆描述文字', duration_sec: 3725 }),
  T(2, { storage: 'cache', bytes: 2 * 1024 ** 2 }),
  T(3, { has_file: false, storage: 'metadata', bytes: 0, source_url: null, uploader: null }),
  T(4),
  T(5, { platform: 'douyin' }),
  ...Array.from({ length: 7 }, (_, i) => T(i + 6)),
  T(20, { media_type: 'video', title: '视频条目：用于验证标签页切换', duration_sec: 95 }),
];

const CONFIG = {
  cache_ttl_seconds: 86400, cache_quota_bytes: 5 * 1024 ** 3, archive_quota_bytes: 20 * 1024 ** 3,
  creator: { max_items: 200, max_start: 60 }, credential_vault: true,
};
const ME = { id: 'acct_1', login_name: 'owner1', tier: 'full', tier_expires_at: null, consent_required: false, recovery_confirmed: true, status: 'active' };

/** 1 秒 8kHz 单声道静音 WAV —— 空 body 会让 play() 直接 reject，测不到「暂停」状态 */
function silentWav(seconds = 1, rate = 8000) {
  const n = rate * seconds;
  const b = Buffer.alloc(44 + n);
  b.write('RIFF', 0); b.writeUInt32LE(36 + n, 4); b.write('WAVE', 8);
  b.write('fmt ', 12); b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22);
  b.writeUInt32LE(rate, 24); b.writeUInt32LE(rate, 28); b.writeUInt16LE(1, 32); b.writeUInt16LE(8, 34);
  b.write('data', 36); b.writeUInt32LE(n, 40);
  b.fill(128, 44);
  return b;
}
const WAV = silentWav();

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  fs.mkdirSync(OUT, { recursive: true });
  const srv = await serve();
  const base = `http://127.0.0.1:${srv.address().port}/getmedia/owner/`;
  const browser = await puppeteer.launch({
    executablePath: EXE,
    headless: true,
    args: ['--autoplay-policy=no-user-gesture-required', '--no-sandbox'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 900, deviceScaleFactor: 2 });
  await page.setRequestInterception(true);

  page.on('request', (req) => {
    const u = new URL(req.url());
    if (!u.pathname.startsWith('/getmedia/owner/api/')) return req.continue();
    const p = u.pathname.replace('/getmedia/owner/api', '');
    const json = (o) => req.respond({ status: 200, contentType: 'application/json', body: JSON.stringify(o) });
    if (p === '/v2/config/owner') return json(CONFIG);
    if (p === '/account/me') return json(ME);
    if (p === '/v2/tracks') return json({ items: ITEMS, count: ITEMS.length });
    if (p.startsWith('/v2/media/')) return req.respond({ status: 200, contentType: 'audio/wav', body: WAV });
    return json({});
  });
  page.on('pageerror', (e) => console.error('[pageerror]', e.message));

  const shot = async (name) => {
    await sleep(220);
    await page.screenshot({ path: path.join(OUT, `${name}.png`) });
    console.log('shot', name);
  };

  /** 在当前 DOM 里按文本点一个元素（每次重新查询，避免句柄过期）。
   *  `prefix: true` 用于标签页——它的文字后面还挂着计数徽标（「视频1」）。 */
  const clickText = async (sel, text, { prefix = false, what = `${sel}「${text}」` } = {}) => {
    const ok = await page.evaluate((sel, text, prefix) => {
      const el = Array.from(document.querySelectorAll(sel))
        .find((e) => (prefix ? e.textContent.trim().startsWith(text) : e.textContent.trim() === text));
      if (!el) return false;
      el.click();
      return true;
    }, sel, text, prefix);
    if (!ok) throw new Error(`点不到 ${what}`);
    await sleep(250);
  };

  /** 直接写搜索框的值并派发 input —— 三击全选 + Backspace 在 type=search 上只删一个字符 */
  const setSearch = async (text) => {
    await page.evaluate((text) => {
      const el = document.querySelector('input[aria-label="搜索媒体库"]');
      const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      set.call(el, text);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }, text);
    await sleep(320);
  };

  /** 勾选当前页前 n 行 */
  const pickRows = async (n) => {
    const boxes = await page.$$('.track-pick input');
    for (const b of boxes.slice(0, n)) await b.click();
    await sleep(180);
    console.log(`  勾选 ${Math.min(n, boxes.length)} 项`);
  };

  const goto = async () => {
    await page.goto(base + 'library', { waitUntil: 'networkidle0' });
    await page.waitForSelector('.track');
    await sleep(150);
  };

  // 1) 勾 4 项 → 输入搜不到的关键词（选中应被清空）
  await goto();
  await pickRows(4);
  await shot('01-selected-4');
  await setSearch('zzz-查无此条');
  await shot('02-empty-after-search');

  // 2) 清搜索 → 勾 2 项 → 切到「视频」标签页（选中应被清空）
  await setSearch('');
  await pickRows(2);
  await shot('03-before-tab-switch');
  await clickText('.chips .chip', '视频', { prefix: true, what: '视频标签页' });
  await page.waitForSelector('.track');
  await shot('04-after-tab-switch');

  // 3) 单条删除确认框 / 批量删除确认框
  await clickText('.chips .chip', '音频', { prefix: true, what: '音频标签页' });
  await page.waitForSelector('.track-actions .btn-danger');
  const delBtns = await page.$$('.track-actions .btn-danger');
  console.log('  可点删除按钮数量 =', delBtns.length);
  await delBtns[0].click();
  await sleep(250);
  await shot('05-confirm-single');
  const focusInCancel = await page.evaluate(() => {
    const a = document.activeElement;
    return a ? a.textContent.trim() : '(none)';
  });
  console.log('  确认框默认焦点 =', focusInCancel);
  await clickText('.modal button', '取消', { what: '确认框取消' });

  await pickRows(4);
  await clickText('.selectbar button', '批量删除', { what: '批量删除' });
  await sleep(250);
  await shot('06-confirm-batch');
  await clickText('.modal button', '取消', { what: '确认框取消' });

  // 4) 无文件条目：播放/下载禁用
  await page.evaluate(() => {
    document.querySelectorAll('.track-pick input').forEach((i) => { if (i.checked) i.click(); });
  });
  await sleep(250);
  const rowHandle = await page.evaluateHandle(() => Array.from(document.querySelectorAll('.track'))
    .find((li) => li.textContent.includes('曲目 3')));
  const rowEl = rowHandle.asElement();
  if (!rowEl) throw new Error('找不到「曲目 3」那一行');
  await rowEl.screenshot({ path: path.join(OUT, '07-nofile-row.png') });
  console.log('shot 07-nofile-row');

  // 5) 播放：点第一首，再点第二首（同时只应有一个播放条）
  const clickPlay = async (title) => {
    const ok = await page.evaluate((title) => {
      const li = Array.from(document.querySelectorAll('.track')).find((x) => x.textContent.includes(title));
      const b = li && li.querySelector('.track-actions button');
      if (!b) return false;
      b.click();
      return true;
    }, title);
    if (!ok) throw new Error(`点不到「${title}」的播放按钮`);
    await sleep(450);
  };
  await clickPlay('曲目 2');
  await shot('08-playing-first');
  const btnText = await page.evaluate(() => {
    const li = Array.from(document.querySelectorAll('.track')).find((x) => x.textContent.includes('曲目 2'));
    return li.querySelector('.track-actions button').textContent.trim();
  });
  console.log('  播放中第一首的按钮文字 =', btnText);
  await clickPlay('曲目 4');
  const players = await page.$$eval('.player-inline', (els) => els.length);
  console.log('  同时展开的播放器数量 =', players);
  await shot('09-playing-second');

  // 6) 分页：勾一条 → 翻到第 2 页（选中应被清空、序号接着 6）
  await goto();
  await pickRows(1);
  await shot('10-page1-selected');
  await clickText('.pager button', '2', { what: '第 2 页' });
  await page.waitForSelector('.track');
  const page2 = await page.evaluate(() => ({
    firstNo: document.querySelector('.track-no')?.textContent.trim(),
    selected: document.querySelector('.selectbar.on') ? 'still-on' : 'cleared',
  }));
  console.log('  第 2 页首条序号 =', page2.firstNo, '/ 选中 =', page2.selected);
  await shot('11-page2-cleared');

  // 6.5) 每页条数：展开下拉、切到 20 条
  await goto();
  await page.waitForSelector('.pager .pager-size');
  await page.click('.pager .pager-size .select-trigger');
  await sleep(250);
  await shot('13-page-size-open');
  await page.evaluate(() => {
    const o = Array.from(document.querySelectorAll('.pager .pager-size [role="option"]'))
      .find((x) => x.textContent.trim() === '20 条');
    if (!o) throw new Error('每页条数里没有「20 条」');
    o.click();
  });
  await sleep(400);
  const rows20 = await page.$$eval('.track', (e) => e.length);
  const pager20 = await page.evaluate(() => document.querySelector('.pager')?.textContent?.trim() ?? '(无分页条)');
  console.log('  切到 20 条后行数 =', rows20, '/ 分页条 =', pager20);
  // 20 条一页时列表比视口长，分页条在最底部，得滚下去才看得见
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await sleep(300);
  await shot('14-page-size-20');
  // 分页条贴在视口底部时再展开一次「每页」：菜单必须向上弹，否则 10/20 条会被裁在屏幕外
  await page.click('.pager .pager-size .select-trigger');
  await sleep(300);
  const menuBox = await page.evaluate(() => {
    const m = document.querySelector('.pager .pager-size .select-menu');
    if (!m) return null;
    const r = m.getBoundingClientRect();
    return { top: Math.round(r.top), bottom: Math.round(r.bottom), vh: window.innerHeight,
             opts: Array.from(m.querySelectorAll('[role="option"]')).map((o) => o.textContent.trim()) };
  });
  console.log('  菜单位置 =', JSON.stringify(menuBox));
  await shot('14b-page-size-menu-up');
  await page.keyboard.press('Escape');
  await sleep(150);
  // 还原成 5 条，别让后面的窄屏截图继承 20 条
  await page.evaluate(() => localStorage.removeItem('getmedia.library.pageSize'));

  // 7) 窄屏
  await page.setViewport({ width: 390, height: 844, deviceScaleFactor: 2 });
  await goto();
  await sleep(300);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  console.log('  窄屏横向溢出 =', overflow);
  await shot('12-narrow-390');
  // 窄屏分页条：页码 + 每页下拉 + 提示 + 下一页 一起 wrap，别横向溢出
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await sleep(300);
  const narrowOverflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  console.log('  窄屏滚到底后横向溢出 =', narrowOverflow);
  await shot('15-narrow-pager');

  await browser.close();
  srv.close();
  console.log('done ->', OUT);
}

main().catch((e) => { console.error(e); process.exit(1); });
