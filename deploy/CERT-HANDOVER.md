# 证书续期：交接与失败处置

对应方案 `docs/PLAN.md` §10（证书与端口）、§15 第 8 步、§16 风险。

**背景**：443 的 server 块被 GetAudio、GetVideo、GetMedia 共用同一张 IP 证书；证书总寿命约 6 天 16 小时（约 160 小时）。
现在续期任务在 GetAudio 名下；GetAudio、GetVideo 将在 GetMedia 开发完成后退役，所以续期任务最终要由 GetMedia 维护。
交接过程如果出现"无人续期"或"两个任务互相覆盖"，证书会过期，三个入口同时挂掉——所以步骤要一步一步来。

**红线**
- 交接完成之前，GetMedia **不得改动、停用或复制**现有续期任务。
- 证书文件路径与 443 块里的 `ssl_certificate` 路径**保持不变**；交接只改"谁来续期、续期后 reload nginx"。
- **任何时刻只能有一个续期任务处于启用状态。**
- 退役 GetAudio / GetVideo **不得先于**续期交接，且必须等新任务自然续期成功一次之后。
- 本目录的模板默认**不启用**；Agent 不自行启用。

## 0. 必须先确认的一个假设

`cert/getmedia-cert-renew.sh` 按 **certbot** 编写：用 certbot 已保存的续期配置（`certbot renew --cert-name <名字>`）重新签发，
签发方式沿用现有证书当初的设置。**我在开发环境里看不到服务器上现有续期任务实际用的是什么**，所以这是假设，不是事实。

- 现有任务若用 certbot：直接用。
- 若是别的 ACME 客户端（acme.sh、lego 等）：只需改脚本里 `run_acme` 一个函数，其余（有效期判断、校验、`nginx -t` 后再 reload、失败告警、加锁）不变。改完请先跑 `--dry-run`。

## 1. 只读确认现状（不改任何东西）

```bash
sudo sh deploy/bin/probe-server.sh | sed -n '/证书/,/运行用户/p'
```

记下：现有续期任务的名称与触发方式、证书文件路径、证书到期时间、certbot 里这张证书的名字（`sudo certbot certificates`）。
把输出（不含任何密钥）发给开发者核对，确认第 0 节的假设。

## 2. 安装新任务，但不启用

```bash
sudo install -m 755 deploy/cert/getmedia-cert-renew.sh       /usr/local/sbin/getmedia-cert-renew.sh
sudo install -m 600 deploy/cert/cert-renew.env.example       /etc/getmedia/cert-renew.env     # 然后编辑：CERT_FILE、CERT_NAME
sudo install -m 644 deploy/cert/getmedia-cert-renew.service  /etc/systemd/system/
sudo install -m 644 deploy/cert/getmedia-cert-renew.timer    /etc/systemd/system/
sudo systemctl daemon-reload                                  # 注意：不要 enable、不要 start
```

`CERT_FILE` 必须与 443 块里 `ssl_certificate`、以及 `getmedia.env` 里 `GETMEDIA_CERT_PATH` 是同一个文件（到期告警读的就是它）。

## 3. 演练（不消耗正式额度）

```bash
sudo /usr/local/sbin/getmedia-cert-renew.sh --dry-run
```

期望输出"演练通过（未改动证书，未 reload nginx）"。这一步用的是 `certbot renew --dry-run`（测试环境），不会改动证书。
同时确认告警通道：`sudo getmedia-admin mail-test`。

## 4. 切换（同一个操作窗口内完成）

```bash
# 4.1 停用旧任务（名称以第 1 步为准；timer 用 disable，cron 则注释掉那一行）
sudo systemctl disable --now <旧续期 timer 名>
# 4.2 立刻启用新任务
sudo systemctl enable --now getmedia-cert-renew.timer
# 4.3 立即手动触发一次真实续期
sudo /usr/local/sbin/getmedia-cert-renew.sh --force
```

4.1 与 4.2 之间不要停顿；若 4.3 失败，立刻执行第 6 节的回退。

## 5. 切换后检查（全部通过才算交接成功）

```bash
echo | openssl s_client -connect 127.0.0.1:443 2>/dev/null | openssl x509 -noout -enddate     # 有效期已更新
sudo nginx -t
sudo sh deploy/bin/verify-deploy.sh after /root/gm-before.txt
for p in /getaudio/ /getvideo/ /getmedia/api/health; do curl -sk -o /dev/null -w "$p %{http_code}\n" https://127.0.0.1$p; done
systemctl list-timers | grep -i cert        # 只应看到一个处于启用状态的续期 timer
```

三个入口（`/getaudio/`、`/getvideo/`、`/getmedia/`）都正常；到期告警会在下一次每小时检查时读到新有效期。

## 6. 观察与回退

- **观察**：新任务**自然续期一次成功**后（证书只有约 6 天 16 小时，所以大约 3–6 天内会发生），才可进入退役步骤。
  日志：`journalctl -u getmedia-cert-renew`。
- **回退**（新任务失败时）：`sudo systemctl disable --now getmedia-cert-renew.timer`，重新启用旧任务；
  **保留旧任务的备份直到退役完成**。
- **退役 GetAudio、GetVideo**（在上面全部完成之后）：先在 443 块删除它们的 include 与前缀，`nginx -t && reload`，再停服务。

## 7. 续期失败时的处置

先看是哪一道保险响了：

| 信号 | 含义 |
|---|---|
| 邮件 `[GetMedia 告警] cert_renew_failed` | 续期脚本某一步失败（邮件正文是失败原因的简短类别） |
| 邮件 `[GetMedia 告警] cert_expiring` | 证书剩余 <24 小时（脚本之外的第二道保险） |
| 邮件 `[GetMedia 告警] cert_unreadable` | 服务读不到或解析不了 `GETMEDIA_CERT_PATH` |

处置顺序：

1. 当前证书还没过期时，别慌：`sudo /usr/local/sbin/getmedia-cert-renew.sh --force`，看输出里是哪一步失败。
2. "ACME 续期命令失败"：检查 80 端口的 ACME 验证是否仍可达（80 端口的 server 块与 ACME 配置**长期保留**，GetMedia 不改它）、certbot 日志（`/var/log/letsencrypt/`）、是否触发了签发频率限制。
3. "有效期没有变化"：命令返回成功但证书没换，通常是续期配置指向了别的证书名或别的文件路径，核对 `CERT_NAME` / `CERT_FILE`。
4. "nginx -t 未通过"：**脚本已放弃 reload，正在运行的仍是旧配置**；先修配置再手动 `systemctl reload nginx`。不要在 `-t` 未通过时强行 reload。
5. 证书已经过期：三个入口都会失效。先手动续期并 reload；这段时间的 App 无法联网，但**已登录用户的本地功能不受影响**（方案 §4.5）。
6. 反复失败时，暂时不要同时启用两个续期任务；回到第 6 节"回退"。
