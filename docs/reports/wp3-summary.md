# WP3 反馈 — 总结

分支 `wp3-feedback`（基于 `wp2-tier`）。服务端测试 114 项 + 工具测试，全部通过（沙箱 Python 3.13）。

## 完成
- `POST /v2/feedback`（multipart）：文字/图片/诊断任意组合；限流（账号 5/天、IP 10/天、间隔 ≥30 秒，落库）；`idempotency_key` 按账号隔离，重复提交返回原编号。
- 图片：魔数 + Pillow 真实解码、25 MP 像素上限、EXIF/GPS 清除（新建空白图像再重编码）、最长边 ≤4096、随机文件名、目录 700/文件 600、全站 2 GB 上限（90% 告警、满则拒图）。
- 先落库、后台线程异步发信：数据库待发队列 + 退避（1m/5m/30m/2h/6h，共 6 次）；重启后继续；每日发信上限；无邮件配置时只入库。
- 邮件：纯文本；无 Reply-To；头部字段去换行；失败日志只含异常类别与 SMTP 数字码（测试证明不含地址/密码）。
- 保留期清理 `cleanup()`：图片 90 天、文字 180 天（后台线程每小时跑一次）；`delete_for_user()` 供 WP4 注销使用。
- 管理 CLI：`python -m getmedia.cli feedback list|show|export|mark-handled`。
- 迁移 v3：`feedback` 表。契约 `docs/contracts/api.md` 已更新（图片 5 MB/张、合计 12 MB，与方案一致）。

## 未验证 / 注意
- 未对真实 SMTP 发信（沙箱无法解析 smtp.qq.com）；需在服务器用 env 文件实测一次（`smtplib` 路径只由假发送器覆盖）。
- `contact` 经换行清理后放入正文；诊断过滤是关键字级别（含 session 字样的值会被丢弃，属有意保守）。
- 反馈图片总量统计用 `image_bytes` 列（重编码后大小），不扫描目录。
- 退避和日上限依赖进程内 `process_queue`，WP4 的 systemd 单元需保证只有一个 worker。
