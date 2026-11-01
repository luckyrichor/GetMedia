import { fileURLToPath } from 'node:url';
import preact from '@preact/preset-vite';
import { defineConfig } from 'vitest/config';

const here = (p: string) => fileURLToPath(new URL(p, import.meta.url));

export default defineConfig({
  plugins: [preact()],
  resolve: { alias: { '@shared': here('./shared') } },
  // 测试要读仓库根目录下的 docs/legal 协议种子（?raw），默认只允许读工程目录内
  server: { fs: { allow: [here('..')] } },
  test: { environment: 'jsdom', include: ['tests/**/*.test.{ts,tsx}'], restoreMocks: true },
});
