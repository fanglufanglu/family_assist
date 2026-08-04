# 亲情帮帮 Android MVP

这是“亲情帮帮”的 Android MVP 工程，用来验证“长辈发起协助，家属看到长辈手机屏幕内容”的核心链路。

## 当前能力

- 一个 APP，支持“长辈模式”和“家属模式”
- 应用名称：亲情帮帮
- Android applicationId：`com.qinqing.bangbang`
- Logo 资源：见 `brand/` 和 Android launcher icon 资源
- 亲属绑定：长辈生成 6 位绑定码，家属输入绑定码后获得本机授权 token
- 长辈端发起协助请求
- 长辈端通过 Android MediaProjection 授权共享屏幕截图
- 家属端轮询协助请求，并查看最新屏幕截图
- 家属端点击截图位置，长辈端可通过悬浮窗看到红圈提示
- 长辈端手动隐私遮罩：遇到密码、验证码、支付页面时可立即遮罩家属端画面
- 可选敏感页面自动检测：开启无障碍服务后，检测支付、银行、验证码等关键词并自动遮罩
- 长辈端可以停止协助
- 本地 Node.js relay 服务，便于两台手机在同一 Wi-Fi 下联调

## 暂不包含

- 远程点击/滑动控制
- APP 离线推送提醒
- 语音通话
- 亲属实名绑定/人脸校验
- 端到端加密

这些能力应该在验证需求后分阶段加入，尤其远程控制必须等风控成熟后再开放。

## 运行 relay

在电脑上执行：

```bash
cd relay
node server.js
```

查看电脑局域网 IP：

```bash
ipconfig getifaddr en0
```

如果 IP 是 `192.168.1.10`，两台手机里的 Relay 地址填写：

```text
http://192.168.1.10:8787
```

两台手机需要填写同一个家庭码，例如：

```text
family001
```

## 打开 Android 工程

用 Android Studio 打开本目录：

```text
family-assist-android-mvp
```

Android Studio 会下载 Android Gradle Plugin 和 Gradle 依赖。连接 Android 手机后，直接运行 `app`。

推荐本机环境：

- Android Studio 最新稳定版
- Android SDK Platform 35
- Android SDK Build-Tools 35.x
- JDK 17，Android Studio 通常会自带
- 两台 Android 8.0+ 真机；屏幕共享、悬浮窗、无障碍能力不建议只用模拟器验证

## 测试流程

1. 电脑启动 relay 服务。
2. 两台 Android 手机连接同一个 Wi-Fi。
3. 两台手机安装并打开 APP。
4. 两台手机填写同一个 Relay 地址和家庭码。
5. 长辈手机点击“长辈：生成亲属绑定码”，把 6 位码告诉家属。
6. 家属手机输入绑定码，点击“家属：用绑定码绑定长辈”。
7. 长辈手机进入“长辈模式”，点击“找家人帮忙”，同意系统屏幕录制授权。
8. 家属手机进入“家属模式”，看到协助请求和长辈端屏幕截图流。
9. 家属点击截图上的某个位置，长辈端如果开启了“画圈浮层”，当前屏幕上会出现红圈提示。
10. 长辈遇到支付、密码、验证码等页面时，可点击“打开隐私遮罩”；家属端会看到“隐私保护中”的遮罩画面。

## 权限说明

- 屏幕录制：长辈每次协助时由系统弹窗授权。
- 悬浮窗：长辈要看到家属画圈提示，需要在“开启画圈浮层”时允许悬浮窗权限。
- 无障碍服务：只用于 MVP 的敏感页面自动检测。用户必须手动到系统设置开启；生产版本需要更严格的权限说明、最小化采集和合规审查。

## 本机编译验证

打开 Android Studio 后：

1. 等待 Gradle sync 完成。
2. 如果提示缺少 SDK，点击提示安装 Android SDK Platform 35。
3. 连接真机，打开 USB 调试。
4. 运行 `app`。
5. 也可以在 Android Studio Terminal 中执行：

```bash
./gradlew :app:assembleDebug
```

## 重新生成 Logo PNG

Logo 的可编辑源文件在 `brand/qinqing-bangbang-logo.svg`。如需重新生成 Android PNG 图标：

```bash
javac -d /tmp/family_assist_logo scripts/GenerateLogoAssets.java
java -Djava.awt.headless=true -cp /tmp/family_assist_logo GenerateLogoAssets
```

## 后续产品化建议

- 用 FCM + 国内厂商推送替代家属端轮询
- 引入 WebRTC DataChannel/MediaStream 替代截图轮询
- 增加亲属白名单、实名绑定、设备绑定
- 敏感页面自动暂停共享，例如支付、短信验证码、银行、贷款
- 所有协助会话记录审计日志
- 第二阶段只开放“画圈标注”，第三阶段再评估远程控制
