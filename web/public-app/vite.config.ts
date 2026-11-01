import { fileURLToPath } from 'node:url';
import preact from '@preact/preset-vite';
import { defineConfig } from 'vite';

const here = (p: string) => fileURLToPath(new URL(p, import.meta.url));

// 网页挂在 443 的 /getmedia/ 前缀下（方案 §4.6）；接口为同源的 /getmedia/api/。
export default defineConfig({
  root: here('.'),
  base: '/getmedia/',
  plugins: [preact()],
  resolve: { alias: { '@shared': here('../shared') } },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    target: 'es2020',
    cssCodeSplit: false,
    sourcemap: false,
    assetsInlineLimit: 0, // 不生成 data: URI：CSP 是 default-src 'self'
    modulePreload: false,
  },
});
