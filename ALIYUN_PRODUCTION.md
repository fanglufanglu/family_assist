# 亲情帮帮阿里云正式环境

当前阶段暂不购买域名，使用中国香港 ECS 公网 IP：

```text
http://47.238.240.30
```

这套配置用于第一版正式验证。后续购买域名后，应切换为 HTTPS：

```text
https://api.qinqingbangbang.com
```

## 服务组成

- `family-assist-relay`：Node.js relay，负责亲属绑定、协助会话、WebRTC 信令、画圈提示、远程操作指令、审计和崩溃日志。
- `nginx`：对外监听 `80`，反向代理到本机 `8787`。
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

在 ECS Workbench 终端执行：

```bash
cd /opt/family_assist
git pull
chmod +x scripts/install-aliyun-production.sh scripts/check-aliyun-production.sh
TURN_PASSWORD='替换成一串长随机密码' bash scripts/install-aliyun-production.sh
```

验证：

```bash
bash scripts/check-aliyun-production.sh
curl http://47.238.240.30/health
curl http://47.238.240.30/api/ice-config
```

## 日常更新

```bash
cd /opt/family_assist
git pull
systemctl restart family-assist-relay
bash scripts/check-aliyun-production.sh
```

## 数据持久化

relay 会把非屏幕内容状态保存到：

```text
/var/lib/family-assist-relay/relay-state.json
```

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

## 正式发布前仍需补齐

- 域名和 HTTPS。
- TURN 密码定期轮换。
- 云监控告警：CPU、内存、磁盘、公网流量、服务健康检查。
- 数据库迁移：用户增长后从 JSON 状态文件迁移到 PostgreSQL/MySQL。
- 完整应用市场资料：隐私政策 URL、用户协议 URL、权限说明截图、客服电话/邮箱、公司主体。
