// 产物体积检查：JS+CSS gzip 合计不超过 60 KB（方案 §4.6 预期 30–60 KB）。
import { readdirSync, readFileSync, statSync } from "node:fs";
import { gzipSync } from "node:zlib";
import { join } from "node:path";

const dir = new URL("../public-app/dist/", import.meta.url).pathname;
let total = 0;
const walk = (d) => readdirSync(d).forEach((f) => {
  const p = join(d, f);
  if (statSync(p).isDirectory()) return walk(p);
  const gz = gzipSync(readFileSync(p)).length;
  if (/\.(js|css)$/.test(f)) total += gz;
  console.log(`${p.slice(dir.length).padEnd(36)} ${String(statSync(p).size).padStart(7)} B  gzip ${gz} B`);
});
walk(dir);
console.log(`JS+CSS gzip 合计 ${(total / 1024).toFixed(1)} KB（上限 60 KB）`);
if (total > 60 * 1024) { console.error("体积超限"); process.exit(1); }
