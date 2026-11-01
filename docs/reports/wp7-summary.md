# WP7 小结：web-public（公开网页）

分支 `wp7-web-public`。接力：前一段（另一个 agent）完成网页工程、共享层、全部页面与 42 项组件测试；本段补齐真实浏览器联调、体积脚本与遗漏测试。

## 验证结果（沙箱，Chromium + Python 3.13 + Node 22）
- **组件/单元测试** 59 项通过（含 `safeNext` 跳转白名单专门测试）；`tsc` 通过。
- **端到端 7 项通过**（`web/e2e`）：真实服务端 + 构建产物放在同一个 https 来源的 `/getmedia/` 下（模拟 nginx 去前缀与 `try_files` 回退）：
  - 会话 Cookie `__Secure-gm_session`：`HttpOnly`、`Secure`、`SameSite=Strict`、`Path=/getmedia/api/`；`document.cookie` 读不到；
  - 恢复码、令牌不进 localStorage / sessionStorage（只有 `getmedia:` 前缀的非敏感键）；
  - 全部请求同源，无第三方请求，无 CSP 违规；
  - CSRF：缺 `X-GetMedia-CSRF` 或 Origin 不符的写请求被服务端 403，GET 不受影响；
  - 注册→保存恢复码→改密码→退出→旧密码失败/新密码成功；找回；注销；反馈带图片（落库、诊断含 `channel=web`）；协议页含表格；
  - **360 px 宽度**下首页、注册、登录、找回、两份协议、联系作者、404 页均无横向滚动；
  - 首页无提取/播放入口。
- **体积**：JS 18.5 KB + CSS 2 KB（gzip），合计 20 KB，远低于 60 KB 上限（`npm run size`）。
- 泄露扫描、heavy/legacy 禁用词扫描（含 `web/public-app`、`web/shared`）通过；构建产物中无邮箱字符串。

## 本段改动
- `web/e2e/`（夹具 + 7 个用例）、`web/scripts/size.mjs`（原 `npm run size` 指向的脚本不存在）、`web/tests/router.test.ts`、DEV.md 增补网页命令。

## 未验证 / 注意
- 只在 Chromium 上测过；Safari/Firefox 未测。真实 nginx 的 CSP 响应头由 deploy 模板测试覆盖，这里用夹具模拟；在真实服务器上需看一眼浏览器控制台无 CSP 报错。
- 夹具对 Cookie `Secure` 用自签名证书（忽略证书错误）；真实 IP 证书下行为应一致，但未实测。
- 无障碍只做了基础（标签、焦点、对比度），没有读屏实测。
- `web/e2e` 不在默认 `pytest` 收集里（与 `server/tests` 的 `conftest.py` 同名冲突），需单独运行。
- 页面里 `登录后跳转` 只接受站内小写路径；下载页的 APK 链接来自服务端 `release.json` 且只接受 `https://`。

## 下一步
WP5a（Android 音频基座）已有另一段推进到“未编译”状态，需要用户在 Mac 上构建回传后才能继续；WP6/WP8 依赖它。
