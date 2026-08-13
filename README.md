# 亲情帮帮 Android

“亲情帮帮”用于长辈向已绑定家属发起手机协助。双方安装同一个 Android 应用，长辈主动共享屏幕后，家属可以查看实时画面、发送画圈提示，并在长辈当次明确授权后远程操作。

## 当前能力

- 手机号和密码注册、登录
- 短信验证码找回密码、账号永久注销
- 长辈邀请家属，绑定请求需长辈确认
- 一个长辈可绑定多位家属，同一时间只允许一位家属协助
- WebRTC 实时屏幕，连接失败时自动使用备用画面
- 家属画圈提示、长辈敏感页面保护
- 长辈当次授权后的远程点击、滑动和系统返回/主页操作
- 双方结束状态同步、后台重要通知
- 屏幕共享异常中断后由服务器自动回收会话，双方可重新发起
- 协助审计和崩溃日志摘要
- PostgreSQL 持久化和每日备份脚本

## 安全边界

- Release 构建禁止明文 HTTP，默认连接 `https://47.238.240.30`
- 密码只通过 HTTPS 传输，并以 PBKDF2-SHA256 带盐哈希保存
- 找回密码验证码仅保存哈希，10 分钟过期并限制尝试次数
- 密码重置后会轮换账号令牌，使旧会话失效
- 请求日志自动脱敏账号令牌、成员令牌和绑定凭据
- 长辈未发起协助时，家属不能查看屏幕
- 远程操作需要长辈每次明确授权，协助结束后自动失效
- 实时屏幕画面默认不落盘

## 本地验证

启动 relay：

```bash
RESET_CODE_EXPOSED=true npm start
```

运行服务端回归：

```bash
npm run test:relay
```

构建 Android：

```bash
./gradlew assembleDebug assembleRelease
```

Debug 构建允许通过 ADB 把 `baseUrl` 指向本机服务；Release 构建仅允许 HTTPS。屏幕共享、悬浮窗、无障碍和厂商后台通知策略仍应在多品牌真机上完成发布前验收。

## 忘记密码短信

relay 通过短信 Webhook 发送验证码：

```text
SMS_WEBHOOK_URL=https://你的短信网关地址
SMS_WEBHOOK_TOKEN=短信网关鉴权令牌
```

Webhook 收到的 JSON：

```json
{
  "phone": "13800000000",
  "code": "123456",
  "purpose": "password_reset",
  "expiresInMinutes": 10
}
```

`RESET_CODE_EXPOSED=true` 只允许用于本地自动化测试，正式服务器严禁开启。未配置短信网关时，应用会友好提示短信服务暂不可用。

## 部署

阿里云 ECS、PostgreSQL、TURN、Nginx 和无域名 IP HTTPS 的步骤见 [ALIYUN_PRODUCTION.md](ALIYUN_PRODUCTION.md)。

## Web 管理后台

管理后台地址为 `/admin/`，使用独立的管理员会话，不复用 APP 账号。开启前必须配置：

```text
ADMIN_USERNAME=admin
ADMIN_PASSWORD=与APP用户密码不同的长随机密码
```

后台密码至少 12 位，生产环境建议使用 20 位以上随机密码。管理员登录与 APP 账号完全隔离；后台接口仅提供脱敏手机号和匿名家庭编号，不返回密码、账号令牌、绑定码或屏幕内容。

后台提供运营总览、用户与亲属关系、协助会话、问题诊断和管理审计。它不提供用户屏幕画面、完整手机号、密码或会话令牌。

登录后可在右上角修改管理员密码。新密码会以 PBKDF2 哈希保存到 Relay 数据目录的 `admin-credential.json`，修改后其他管理员会话会立即失效。

Logo 可编辑实现位于：

- `scripts/GenerateLogoAssets.java`
- `app/src/main/res/drawable/ic_launcher_foreground.xml`
- `app/src/main/res/drawable/ic_launcher_background.xml`
