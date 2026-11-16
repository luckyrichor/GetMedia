# 服务器 IP 平台实测矩阵（2026-11-16）

目的：验证"批量列表由服务端列还是手机列"这个决定（Q6），以及服务器侧能否完成解析与取流。
执行位置：**腾讯云生产服务器本体**（机房网络），不是本机住宅网络。主机地址与 SSH 别名不写入公开仓库。

## 1. 方法与边界

- 把 P2 的 `server/getmedia` 复制到 `/tmp/gm-probe`，用现成的 `/opt/getmedia/venv` 解释器运行探测脚本；
- **没有部署**：`/opt/getmedia`、`/var/lib/getmedia`、systemd、nginx 全部未动；
- 探测脚本只做 GET（列表/详情/播放地址），只读 256 KB 媒体确认 CDN 可达，不落盘、不写库；
- 跑完删除 `/tmp/gm-probe` 与探测数据。

```sh
rsync -az --exclude __pycache__ server/getmedia <prod-host>:/tmp/gm-probe/
/opt/getmedia/venv/bin/python /tmp/gm-probe/platform_probe.py
```

环境：Python 3.13、httpx 0.28.1；时间 2026-11-16 03:1x UTC。

## 2. 结果矩阵

| 平台 | 条件 | 列表 | 置顶 | 昵称搜索 | 单条解析 | 取流 | 结论 |
|---|---|---|---|---|---|---|---|
| B 站 | 未登录，**无**访客 Cookie | ✗ 非 JSON 响应 | ✓ | ✓ | ✗ `412` | — | 裸请求被拦 |
| B 站 | 未登录，**仅访客** `buvid3` | ✓ 43 条 | ✓ `BV1xx411c7DS` | ✓ 335 结果 | ✓ 3 个直链 | ✓ 277 KB / 405 ms | **服务器列可行** |
| B 站 | 带登录 Cookie | 未测（服务器无登录 Cookie） | 同上 | 同上 | 预期更好（更高画质） | — | 待授权后补测 |
| 抖音 | 未登录 | ✗ `403 Blocked by ArgusSecurityPlugin Uifid Not Found` | — | 需登录 | ✗ 设计上拒绝 | — | 机房 IP 被风控 |
| 抖音 | 带 Cookie + 浏览器桥接 | 未测（服务器无 Cookie、未装 Playwright） | — | — | — | — | **手机列兜底** |

## 3. 逐项输出（原文）

```
bili_pinned
  {"http":200,"code":0,"bvid":"BV1xx411c7DS"}                       # 107 ms

bili_space_unsigned            # 不带 wbi 签名的 x/space/arc/search
  {"error":"JSONDecodeError"}                                      # 返回非 JSON（被拦/风控页）

bili_space_signed              # creator_service.preview(space/2/video, start=1, count=3)
  {"creator":"碧诗","total":43,"count":3,"first":"帮不帮？","cookie_source":"guest"}
  # 432 ms

bili_search_nick               # wbi 签名 + keyword=碧诗
  {"http":200,"code":0,"numResults":335}                            # 146 ms

bili_view_playurl              # view + wbi playurl（fnval=1 混流）
  {"video_id":"BV1xx411c7DS","title":"[人生的导师松冈修造]東方修夢造",
   "duration_sec":84.0,"sources":3,"estimated_bytes":4467366,
   "host":"cn-jsnt-ct-01-11.bilivideo.com"}                         # 122 ms

cdn_first_chunk                # 直链首段
  {"cdn_first_chunk_bytes":277881,"ms":405}

douyin_raw_no_cookie
  {"http":403,"bytes":46,"head":"Blocked by ArgusSecurityPlugin Uifid Not Found"}

douyin_provider_no_cookie      # creators/douyin.py 的原生异常
  CreatorUnavailable: 抖音主页接口被 Argus 风控拦截（Blocked by ArgusSecurityPlugin Uifid Not Found）。
  请先导入抖音登录 Cookie，并在服务端安装浏览器桥接…（提示可直接展示）

douyin_resolve_no_cookie       # P2a 的明确拒绝，而不是猜直链
  ResolveError(503): 抖音直链需要授权 Cookie 与浏览器桥接；请在手机端提取…
```

## 4. 结论与对计划的影响

1. **B 站：服务器列可行**（Q6 的「服务器优先」成立）。列表、置顶、昵称搜索、单条解析、CDN 取流全部在机房 IP 上跑通，耗时 100–450 ms。
2. **B 站必须带 WBI 签名**：未签名的空间接口在机房 IP 上直接返回非 JSON，签名后正常 → 不能省 `creator_wbi`。
3. **B 站必须带至少一个访客 Cookie**：`x/web-interface/view` 在没有 `buvid3` 时返回 `412`。这条是本轮实测发现并已修掉的实现缺陷——`extractor.MediaResolver` 现在会像 `creators/` 一样，在缺少配置 Cookie 时自动预热访客 Cookie（`creator_guest`），修完复测即通过。
4. **抖音：机房 IP 被封**（`403 ArgusSecurityPlugin`）。因此抖音默认走「手机列 + `items:import` 回传」，服务端只做去重/筛选/记账；服务端直连要在完成 A/B 授权后再验证是否可用。
5. **抖音带 Cookie 一行仍未测**：服务器上没有任何抖音登录 Cookie，装 Playwright 也属于部署动作，本轮不做。等 P2e 的授权流程上线、你导入一次 Cookie 后再补这一行。
6. **对下载链路的影响**：`/v2/media/{id}` 的"当场解析中转"在 B 站可用；抖音条目在中转时会返回 409/503，客户端需要回退到本地提取——这与 §8.2 的设计一致。

## 5. 复现方式

```sh
# 本机
rsync -az --exclude __pycache__ server/getmedia <prod-host>:/tmp/gm-probe/
scp .run-tmp/platform_probe.py <prod-host>:/tmp/gm-probe/       # 探测脚本不进仓库
# 服务器
ssh <prod-host> 'cd /tmp/gm-probe && /opt/getmedia/venv/bin/python platform_probe.py'
# 清理
ssh <prod-host> 'rm -rf /tmp/gm-probe'
```

脚本源码见本轮对话产物（`.run-tmp/platform_probe.py`，已被 `.gitignore` 忽略）；核心逻辑就是把 `creator_service.preview`、`creator_wbi`、`extractor.MediaResolver` 各跑一遍并计时。
