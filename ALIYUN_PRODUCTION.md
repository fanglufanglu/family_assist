# 亲情帮帮阿里云正式环境

当前阶段不购买域名，使用中国香港 ECS 公网 IP 和短周期 IP 证书：

```text
https://47.238.240.30
```

这套配置可用于第一版正式验证。后续购买域名后，再切换为长期域名地址：

```text
https://api.qinqingbangbang.com
```

## 服务组成

- `family-assist-relay`：Node.js relay，负责亲属绑定、协助会话、WebRTC 信令、画圈提示、远程操作指令、审计和崩溃日志。
- `nginx`：监听 `80/443`，HTTP 自动跳转 HTTPS，并反向代理到本机 `8787`。
- `coturn`：WebRTC TURN 服务，提升不同网络下实时屏幕连接成功率。
- `logrotate`：轮转 `/var/log/family-assist/*.log`。

## 安全组

入方向建议：

```text
80/TCP               0.0.0.0/0
443/TCP              0.0.0.0/0       后续启用 HTTPS 时使用
3478/TCP             0.0.0.0/0
3478/UDP             0.0.0.0/0
49152-65535/UDP      0.0.0.0/0
22/TCP               仅允许你的公网 IP/32
```

删除无用端口：

```text
3389/RDP
```

## 首次部署

在 ECS Workbench 终端执行。正式环境建议先完成 PostgreSQL，再启动 relay：

```bash
cd /opt/family_assist
git pull
chmod +x scripts/install-aliyun-production.sh scripts/check-aliyun-production.sh
DB_PASSWORD='替换成数据库长随机密码' bash scripts/install-postgres-backup.sh
ADMIN_PASSWORD='替换成后台独立长随机密码' TURN_PASSWORD='替换成TURN长随机密码' DB_PASSWORD='同上数据库密码' bash scripts/install-aliyun-production.sh
LE_EMAIL='证书到期通知邮箱' bash scripts/enable-aliyun-ip-https.sh
```

安装脚本会将长辈端屏幕共享心跳超时设为 30 秒。长辈端崩溃、被系统结束或断网后，relay 会自动结束旧会话，避免家属端长时间停留在“协助中”。

管理后台位于 `https://服务器地址/admin/`。`ADMIN_PASSWORD` 至少 12 位，必须与 TURN、PostgreSQL 及 APP 账号密码不同，生产环境建议使用至少 20 位随机字符。后台只展示脱敏业务数据，不提供用户屏幕、密码、账号令牌和完整手机号。

用户连接状态按 Relay 最近收到的认证请求计算：20 秒内有请求或 APP 心跳为在线，否则为离线。该状态只保存在 Relay 进程内存中，不向 PostgreSQL 高频写入，也不展示用户 IP。更新 Relay 后需同步发布本版本 APK，未升级的旧版本仍可通过协助轮询刷新连接状态，但在空闲页面的在线判断不如新版本准确。

管理员、TURN 和 PostgreSQL 必须分别使用三个不同密码，不能复用。推荐在服务器上使用 `openssl rand -hex 24` 分别生成，避免特殊字符进入连接字符串后需要额外转义。

管理员登录后可在后台右上角修改密码。修改后的密码哈希保存在 `/var/lib/family-assist-relay/admin-credential.json`，不会写入明文密码。若管理员忘记修改后的密码，可在 ECS 上执行以下命令，删除覆盖凭据并回退到 systemd 中配置的 `ADMIN_PASSWORD`：

```bash
rm -f /var/lib/family-assist-relay/admin-credential.json
systemctl restart family-assist-relay
```

## PostgreSQL 与每日备份

账号体系、亲属关系和审计记录正式化后，建议准备 PostgreSQL。当前脚本会安装 PostgreSQL、创建数据库、导入表结构，并配置每天凌晨 03:17 自动备份，备份保留 14 天。

```bash
cd /opt/family_assist
git pull
chmod +x scripts/install-postgres-backup.sh
DB_PASSWORD='替换成一串长随机密码，只用字母数字更稳妥' bash scripts/install-postgres-backup.sh
```

备份路径：

```text
/var/backups/family-assist-postgres
```

手动备份：

```bash
/usr/local/bin/family-assist-pg-backup
```

查看备份：

```bash
ls -lh /var/backups/family-assist-postgres
```

验证：

```bash
bash scripts/check-aliyun-production.sh
curl https://47.238.240.30/health
curl https://47.238.240.30/api/ice-config
```

## 日常更新

```bash
cd /opt/family_assist
git pull
systemctl restart family-assist-relay
bash scripts/check-aliyun-production.sh
curl -fsS https://47.238.240.30/health
```

## 数据持久化

当 `DATABASE_URL` 已配置时，relay 会把账号、亲属绑定、邀请确认、会话审计等状态保存到 PostgreSQL。`scripts/install-aliyun-production.sh` 在传入 `DB_PASSWORD` 时会自动写入：

```text
DATABASE_URL=postgresql://family_assist:你的密码@127.0.0.1:5432/family_assist
```

PostgreSQL 表结构在：

```text
db/schema.sql
```

如果未配置 `DATABASE_URL`，relay 才会回退到本地 JSON 状态文件：

```text
/var/lib/family-assist-relay/relay-state.json
```

正式环境不要依赖 JSON 回退。

保存内容：

- 亲属绑定关系
- 当前绑定码状态
- 安全审计记录
- 崩溃日志摘要
- 远程操作授权状态摘要

不会保存：

- 长辈实时屏幕画面
- WebRTC SDP/ICE 会话内容
- 已结束会话的屏幕帧

服务重启后，正在进行的协助会话会自动结束，亲属绑定关系保留。这样能避免重启后出现“家属以为还在看屏幕、长辈以为已经结束”的状态错乱。

## 日志

```bash
tail -f /var/log/family-assist/relay.log
tail -f /var/log/family-assist/relay-error.log
journalctl -u family-assist-relay -n 100 --no-pager
journalctl -u coturn -n 100 --no-pager
```

## 找回密码短信网关

正式环境需要准备短信发送网关，并在 `family-assist-relay.service` 中增加：

```text
Environment=SMS_WEBHOOK_URL=https://你的短信网关地址
Environment=SMS_WEBHOOK_TOKEN=一串独立的长随机令牌
```

然后执行：

```bash
systemctl daemon-reload
systemctl restart family-assist-relay
```

正式环境不要设置 `RESET_CODE_EXPOSED=true`。

## 正式发布前仍需补齐

- 短信服务实名、签名和模板审核。
- TURN 密码定期轮换。
- 云监控告警：CPU、内存、磁盘、公网流量、服务健康检查。
- 完整应用市场资料：隐私政策 URL、用户协议 URL、权限说明截图、客服电话/邮箱、公司主体。
