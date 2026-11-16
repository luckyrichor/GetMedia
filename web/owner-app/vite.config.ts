import { fileURLToPath } from 'node:url';
import preact from '@preact/preset-vite';
import { defineConfig } from 'vite';

const here = (p: string) => fileURLToPath(new URL(p, import.meta.url));

/**
 * owner 管理台：独立构建、独立产物、独立 base。
 *
 * - 页面挂在 /getmedia/owner/，接口走同源的另一条边缘通道 /getmedia/owner/api/（nginx auth_request 守门）；
 * - 与 public-app **没有任何共享产物**：只共用 web/shared 里的类型与无副作用工具，
 *   owner 的页面、导航、接口封装全在 owner-app/src 内，public 产物里不会出现这些字符串。
 */
export default defineConfig({
  root: here('.'),
  base: '/getmedia/owner/',
  plugins: [preact()],
  resolve: { alias: { '@shared': here('../shared') } },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    target: 'es2020',
    cssCodeSplit: false,
    sourcemap: false,
    assetsInlineLimit: 0,
    modulePreload: false,
  },
});
