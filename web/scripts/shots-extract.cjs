/**
 * 「单个提取」页改版自查截图：本地起静态服务 + 拦截接口塞桩数据，
 * 用 Playwright 缓存里的 chromium-headless-shell（**不启动系统安装的 Chrome**）。
 *
 * 产出：local-artifacts/extract-ui/*.png
 */
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const puppeteer = require('/Users/lc/Project/GetMedia/web/node_modules/puppeteer-core');

const DIST = '/Users/lc/Project/GetMedia/web/owner-app/dist';
const OUT = '/Users/lc/Project/GetMedia/local-artifacts/extract-ui';
const EXE = '/Users/lc/Library/Caches/ms-playwright/chromium_headless_shell-1234/chrome-headless-shell-mac-arm64/chrome-headless-shell';
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };

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
const LONG = '【4K 修复·60 帧】这是一个特别特别长的视频标题，用来验证作品列单行省略和悬停看全文的效果，后面还有一堆描述文字和【合集】标记，总之就是很长很长';
const job = (id, over = {}) => ({
  id,
  status: 'done',
  media_type: 'video',
  storage: 'archive',
  input: `https://www.bilibili.com/video/BV1xx411c7m${id}`,
  platform: 'bilibili',
  video_id: `BV1xx411c7m${id}`,
  track_id: id,
  error_code: null,
  error_detail: null,
  title: `任务 ${id}`,
  uploader: id <= 2 ? 'UP 主 甲' : `UP 主 ${id}`,
  bytes: id % 3 === 0 ? 0 : id * 1024 * 1024,
  formats: ['mp4'],
  created_at: now - id * 3600,
  started_at: now - id * 3600 + 1,
  finished_at: now - id * 3600 + 9,
  ...over,
});

const JOBS = [
  job(1, { title: LONG }),
  job(2, { status: 'failed', error_detail: '上游返回 403：该视频为充电专属内容，需要大会员账号才能取流；如果确认账号有权限，请稍后重试，或换一个清晰度再试一次。', bytes: 0, track_id: null }),
  job(3, { storage: 'metadata', title: '只存元数据的任务', bytes: 0, uploader: null }),
  job(4, { status: 'running', title: '正在处理的任务', bytes: 0, track_id: null, finished_at: null }),
  job(5, { status: 'queued', title: '排队中的任务', bytes: 0, track_id: null, finished_at: null }),
  ...Array.from({ length: 15 }, (_, i) => job(i + 6)),
];

/** 批量提取页的桩数据：只为了量「存放 / 格式」拆成两列之后的列宽与表头。 */
const LONG_NICK = '这是一个特别特别长的博主昵称用来验证两行截断和悬停看全文';
const BATCH_JOBS = [
  {
    id: 1, platform: 'bilibili', creator_id: '12345', creator_name: LONG_NICK,
    source_url: 'https://space.bilibili.com/12345', mode: 'latest_n',
    since_ts: null, until_ts: null, limit_n: 20, include_pinned: true,
    storage: 'archive', media_type: 'video', status: 'running', pause_reason: null,
    total: 20, done: 6, failed: 1, estimated_bytes: 300 * 1024 * 1024,
    created_at: now - 3600, updated_at: now - 60, started_at: now - 3500, finished_at: null,
    formats: ['mp4'],
  },
  {
    id: 2, platform: 'douyin', creator_id: '67194609689', creator_name: '短视频博主',
    source_url: 'https://www.douyin.com/user/x', mode: 'all',
    since_ts: null, until_ts: null, limit_n: null, include_pinned: false,
    storage: 'metadata', media_type: 'audio', status: 'done', pause_reason: null,
    total: 34, done: 34, failed: 0, estimated_bytes: 0,
    created_at: now - 7200, updated_at: now - 6000, started_at: now - 7100, finished_at: now - 6000,
    formats: [],
  },
];

const CONFIG = {  cache_ttl_seconds: 86400, cache_quota_bytes: 5 * 1024 ** 3, archive_quota_bytes: 20 * 1024 ** 3,
  creator: { max_items: 200, max_start: 60 }, credential_vault: true,
};
const ME = { id: 'acct_1', login_name: 'owner1', tier: 'full', tier_expires_at: null, consent_required: false, recovery_confirmed: true, status: 'active' };

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  fs.mkdirSync(OUT, { recursive: true });
  const srv = await serve();
  const base = `http://127.0.0.1:${srv.address().port}/getmedia/owner/`;
  const browser = await puppeteer.launch({ executablePath: EXE, headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  // 剪贴板要显式授权，否则 navigator.clipboard.writeText 会拒绝
  await browser.defaultBrowserContext()
    .overridePermissions(new URL(base).origin, ['clipboard-read', 'clipboard-write']);
  await page.setViewport({ width: 1280, height: 900, deviceScaleFactor: 2 });
  await page.setRequestInterception(true);

  let jobs = JOBS;
  page.on('request', (req) => {
    const u = new URL(req.url());
    if (!u.pathname.startsWith('/getmedia/owner/api/')) return req.continue();
    const p = u.pathname.replace('/getmedia/owner/api', '');
    const json = (o) => req.respond({ status: 200, contentType: 'application/json', body: JSON.stringify(o) });
    if (p === '/v2/config/owner') return json(CONFIG);
    if (p === '/account/me') return json(ME);
    if (p === '/v2/extraction-jobs') return json({ jobs });
    if (p === '/v2/creators/jobs') return json({ jobs: BATCH_JOBS });
    if (p.startsWith('/v2/media/')) return req.respond({ status: 200, contentType: 'video/mp4', body: '' });
    return json({});
  });
  page.on('pageerror', (e) => console.error('[pageerror]', e.message));

  const shot = async (name) => {
    await sleep(220);
    await page.screenshot({ path: path.join(OUT, `${name}.png`) });
    console.log('shot', name);
  };
  const goto = async (expectRows = true) => {
    await page.goto(base + 'extract', { waitUntil: 'networkidle0' });
    if (expectRows) await page.waitForSelector('.ex-table tbody tr');
    await sleep(150);
  };
  /** 按文字点一个元素：必须走真实鼠标点击（`el.click()` 对自绘下拉的 trigger 不生效）。 */
  const clickText = async (sel, text, prefix = false) => {
    const handles = await page.$$(sel);
    for (const h of handles) {
      const t = await page.evaluate((el) => (el.textContent || '').trim(), h);
      if (prefix ? t.startsWith(text) : t === text) {
        await h.click();
        await sleep(320);
        return;
      }
    }
    throw new Error(`点不到 ${sel}「${text}」（共 ${handles.length} 个候选）`);
  };

  /** 展开「每页」下拉并选一项 */
  const pickSize = async (label) => {
    await page.click('.box-foot .pager-size .select-trigger');
    await sleep(260);
    await clickText('.box-foot .pager-size [role="option"]', label);
  };
  const setInput = async (label, text) => {
    await page.evaluate((label, text) => {
      const el = document.querySelector(`input[aria-label="${label}"]`);
      const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      set.call(el, text);
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }, label, text);
    await sleep(320);
  };
  const state = async () => page.evaluate(() => ({
    rows: document.querySelectorAll('.ex-table tbody tr').length,
    nos: Array.from(document.querySelectorAll('.ex-table tbody .e-no')).map((e) => e.textContent),
    pager: document.querySelector('.box-foot .pager-total')?.textContent?.trim() ?? '(无)',
    total: document.querySelector('.box-foot .small.muted')?.textContent?.trim() ?? '(无)',
    size: document.querySelector('.box-foot .pager-size .select-value')?.textContent?.trim() ?? '(无)',
  }));

  await page.evaluateOnNewDocument(() => {
    try { localStorage.removeItem('getmedia.extract.pageSize'); } catch (e) { /* ignore */ }
  });

  // 1) 首屏：长标题、失败原因、无文件、行高
  await goto();
  console.log('  首屏 =', JSON.stringify(await state()));
  await shot('01-list');

  // 长标题单元格：没有横向滚动条 + title 是全文
  const cell = await page.evaluate(() => {
    const td = document.querySelector('.ex-table tbody .e-title');
    const main = td.querySelector('.t-main');
    return {
      scrollW: td.scrollWidth, clientW: td.clientWidth,
      overflowX: getComputedStyle(td).overflowX,
      innerOverflow: Array.from(td.querySelectorAll('*')).map((e) => getComputedStyle(e).overflowX),
      title: main.getAttribute('title'),
      ellipsis: getComputedStyle(main).textOverflow,
      whiteSpace: getComputedStyle(main).whiteSpace,
      lineClamp: getComputedStyle(td.querySelector('.bad-text') || main).webkitLineClamp,
    };
  });
  console.log('  作品列 =', JSON.stringify(cell, null, 0));

  // 2) 每页条数：5 → 10 → 20
  await page.click('.box-foot .pager-size .select-trigger');
  await sleep(260);
  await shot('02-page-size-open');
  await clickText('.box-foot .pager-size [role="option"]', '10 条');
  console.log('  切 10 条 =', JSON.stringify(await state()));
  await shot('03-size-10');
  await page.click('.box-foot .pager-size .select-trigger');
  await sleep(260);
  await clickText('.box-foot .pager-size [role="option"]', '20 条');
  console.log('  切 20 条 =', JSON.stringify(await state()));
  await shot('04-size-20');

  // 3) 第 3 页 → 改筛选回第 1 页
  await goto();
  await pickSize('5 条');
  await clickText('.box-foot .pager button', '3');
  console.log('  第 3 页 =', JSON.stringify(await state()));
  await shot('05-page3');
  await setInput('搜索昵称', 'UP 主 1');
  console.log('  筛选后 =', JSON.stringify(await state()));
  await shot('06-filter-back-to-1');

  // 4) 筛选后只剩 2 条：分页器只留「第 1 / 1 页」，每页选择器仍在
  await goto();
  await setInput('搜索昵称', 'UP 主 甲');
  console.log('  只剩 2 条 =', JSON.stringify(await state()));
  await shot('07-filter-few');

  // 5) 总数为 0
  jobs = [];
  await goto(false);
  await page.waitForSelector('.empty-state');
  await shot('08-empty');
  console.log('  空库 =', JSON.stringify(await state()));

  // 5.5) 数量为 0 的状态段：只有「已入库」有值时，其余几段应当明显更淡但仍可点
  jobs = [job(1), job(2), job(3)];
  await goto();
  const segs = await page.evaluate(() => Array.from(document.querySelectorAll('.seg-btn')).map((b) => ({
    label: b.textContent.trim(),
    n: b.querySelector('.seg-n')?.textContent,
    zero: b.querySelector('.seg-n')?.classList.contains('zero') ?? false,
    opacity: b.querySelector('.seg-n') ? getComputedStyle(b.querySelector('.seg-n')).opacity : null,
    disabled: b.disabled,
  })));
  console.log('  状态段 =', JSON.stringify(segs));
  await shot('08b-zero-counts');

  // 6) 表头吸顶：先把列表撑长（20 条一页），再滚到表格中间
  jobs = JOBS;
  await goto();
  await pickSize('20 条');
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await sleep(400);
  const sticky = await page.evaluate(() => {
    const th = document.querySelector('.ex-table thead th');
    const r = th.getBoundingClientRect();
    return { top: Math.round(r.top), position: getComputedStyle(th).position, topVar: getComputedStyle(th).top };
  });
  console.log('  表头位置 =', JSON.stringify(sticky));
  await shot('09-sticky-header');

  // 7) 手机宽度：整页不得横向溢出
  await page.setViewport({ width: 390, height: 844, deviceScaleFactor: 2 });
  await goto();
  await sleep(300);
  const narrow = await page.evaluate(() => ({
    overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    wrapOverflow: (() => {
      const w = document.querySelector('.table-wrap');
      return w ? w.scrollWidth - w.clientWidth : null;
    })(),
    innerScrolls: Array.from(document.querySelectorAll('.ex-table .e-title *'))
      .filter((e) => { const s = getComputedStyle(e); return s.overflowX === 'auto' || s.overflowX === 'scroll'; }).length,
  }));
  console.log('  窄屏 =', JSON.stringify(narrow));
  await shot('10-narrow-390');
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await sleep(300);
  await shot('11-narrow-pager');

  // 8) 回到 1280：量列宽 / 表头 / 每格有没有溢出，并截「展开全文」的样子
  await page.setViewport({ width: 1280, height: 900, deviceScaleFactor: 2 });
  jobs = JOBS;
  await goto();
  const cols = await page.evaluate(() => {
    const t = document.querySelector('.ex-table');
    const wrap = document.querySelector('.table-wrap');
    const head = Array.from(t.querySelectorAll('thead th')).map((th) => ({
      label: th.textContent.trim(), w: Math.round(th.getBoundingClientRect().width),
    }));
    const over = [];
    t.querySelectorAll('tbody tr').forEach((tr) => {
      Array.from(tr.children).forEach((td, i) => {
        if (td.scrollWidth > td.clientWidth + 1) {
          over.push({ col: head[i]?.label, cls: td.className, s: td.scrollWidth, c: td.clientWidth });
        }
      });
    });
    return {
      head, total: Math.round(t.getBoundingClientRect().width),
      wrapOver: wrap.scrollWidth - wrap.clientWidth,
      cellOverflow: over,
    };
  });
  console.log('  表头/列宽 =', JSON.stringify(cols));
  // 点第一条的标题展开
  await page.click('.ex-table tbody .t-toggle');
  await sleep(260);
  const expanded = await page.evaluate(() => {
    const tr = document.querySelector('.ex-table tbody tr');
    const main = tr.querySelector('.t-main');
    return {
      open: main.classList.contains('open'),
      aria: tr.querySelector('.t-toggle').getAttribute('aria-expanded'),
      h: Math.round(tr.getBoundingClientRect().height),
      lines: Math.round(main.getBoundingClientRect().height / parseFloat(getComputedStyle(main).lineHeight)),
    };
  });
  console.log('  展开后 =', JSON.stringify(expanded));
  await shot('12-expanded');

  // 8.5) 复制：右击出菜单 → 点「复制标题」→ toast；再验证文字能拖选
  await goto();
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await sleep(200);
  const titleCell = await page.$('.ex-table tbody .e-title');
  const box = await titleCell.boundingBox();
  await page.mouse.click(box.x + 40, box.y + 18, { button: 'right' });
  await sleep(250);
  const menu = await page.evaluate(() => ({
    items: Array.from(document.querySelectorAll('.ctx-menu [role="menuitem"]')).map((b) => b.textContent.trim()),
    top: Math.round(document.querySelector('.ctx-menu')?.getBoundingClientRect().top ?? -1),
    left: Math.round(document.querySelector('.ctx-menu')?.getBoundingClientRect().left ?? -1),
  }));
  console.log('  右键菜单 =', JSON.stringify(menu));
  await shot('14-ctx-menu');

  await clickText('.ctx-menu [role="menuitem"]', '复制标题');
  await sleep(320);
  const copied = await page.evaluate(async () => ({
    toast: document.querySelector('.toast-t')?.textContent ?? null,
    menuGone: document.querySelector('.ctx-menu') === null,
    clip: await navigator.clipboard.readText().catch(() => '(读不到)'),
  }));
  console.log('  复制结果 =', JSON.stringify(copied));
  await shot('15-copied');

  // 拖选：真的按住鼠标从标题开头拖到第一行末尾。
  // 要验证两件事：① 文字能选中（返回非空）；② 拖选不会顺手把行展开（click 守卫）。
  await page.bringToFront();
  // 无头窗口没有焦点时 Chrome 不绘制选区高亮，截图上会「看着没选中」；
  // 打开焦点模拟，选区才会真正画出来。
  const cdp = await page.createCDPSession();
  await cdp.send('Emulation.setFocusEmulationEnabled', { enabled: true });
  const mainBox = await (await page.$('.ex-table tbody .t-main')).boundingBox();
  // 落点要在**第一行文字**里（height/2 正好卡在两行之间，会选不到任何东西）
  await page.mouse.move(mainBox.x + 2, mainBox.y + 9);
  await page.mouse.down();
  await page.mouse.move(mainBox.x + mainBox.width - 10, mainBox.y + 9, { steps: 20 });
  await page.mouse.up();
  await sleep(200);
  const sel = await page.evaluate(() => {
    const s = window.getSelection();
    const tr = document.querySelector('.ex-table tbody tr');
    return {
      text: s.toString().slice(0, 30),
      len: s.toString().length,
      expanded: tr.querySelector('.t-main').classList.contains('open'),
    };
  });
  console.log('  拖选 =', JSON.stringify(sel));
  await shot('16-selection');

  // 9) 批量提取页：同样的表头/列宽实测
  await page.goto(base + 'creators', { waitUntil: 'networkidle0' });
  await page.waitForSelector('.cr-table tbody tr[data-job]');
  await sleep(300);
  const bcols = await page.evaluate(() => {
    const t = document.querySelector('.cr-table');
    const wrap = t.closest('.table-wrap');
    const head = Array.from(t.querySelectorAll('thead th')).map((th) => ({
      label: th.textContent.trim(), w: Math.round(th.getBoundingClientRect().width),
    }));
    const over = [];
    t.querySelectorAll('tbody tr[data-job]').forEach((tr) => {
      Array.from(tr.children).forEach((td, i) => {
        if (td.scrollWidth > td.clientWidth + 1) {
          over.push({ col: head[i]?.label, cls: td.className, s: td.scrollWidth, c: td.clientWidth });
        }
      });
    });
    const nick = document.querySelector('.cr-table .who-name');
    return {
      head, total: Math.round(t.getBoundingClientRect().width),
      wrapOver: wrap ? wrap.scrollWidth - wrap.clientWidth : null,
      cellOverflow: over,
      nickClamp: getComputedStyle(nick).webkitLineClamp,
      nickOverflow: getComputedStyle(nick).overflowX,
    };
  });
  console.log('  批量页表头/列宽 =', JSON.stringify(bcols));
  await shot('13-batch-columns');

  await browser.close();
  srv.close();
  console.log('done ->', OUT);
}

main().catch((e) => { console.error(e); process.exit(1); });
