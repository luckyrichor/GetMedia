import { fileURLToPath } from 'node:url';
import preact from '@preact/preset-vite';
import { defineConfig } from 'vitest/config';

const here = (p: string) => fileURLToPath(new URL(p, import.meta.url));

export default defineConfig({
  plugins: [preact()],
  // 与 owner-app/vite.config.ts 保持一致：页脚读 __BUILD__，测试环境给个固定值
  define: { __BUILD__: JSON.stringify('test') },
  resolve: { alias: { '@shared': here('./shared') } },
  // 测试要读仓库根目录下的 docs/legal 协议种子（?raw），默认只允许读工程目录内
  server: { fs: { allow: [here('..')] } },
  test: { environment: 'jsdom', include: ['tests/**/*.test.{ts,tsx}', 'owner-app/tests/**/*.test.{ts,tsx}'], restoreMocks: true },
});
