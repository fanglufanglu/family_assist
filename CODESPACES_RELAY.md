# GitHub Codespaces Relay

当前项目已经配置好 Codespaces 临时 relay。

## 使用步骤

1. 把本项目推到 GitHub 仓库。
2. 在 GitHub 仓库页面点击 `Code` -> `Codespaces` -> `Create codespace on main`。
3. Codespace 打开后会自动执行：

```bash
bash scripts/start-codespaces-relay.sh
```

4. 打开 Codespaces 的 `Ports` 面板。
5. 找到 `8787` 端口，确认 Visibility 是 `Public`。
6. 复制转发 URL，形如：

```text
https://xxxx-8787.app.github.dev
```

7. 两台 Android 手机上的 Relay 地址都填这个 HTTPS URL。

## 手动启动

如果 relay 没有自动启动，在 Codespaces Terminal 执行：

```bash
bash scripts/start-codespaces-relay.sh
```

查看日志：

```bash
tail -f .codespaces/relay.log
```

测试健康检查：

```bash
curl https://你的-codespace-8787.app.github.dev/health
```

返回：

```json
{"ok":true}
```

## 注意

- Codespaces 是临时验证方案，不适合生产。
- 当前 relay 使用内存存储，Codespace 重启后绑定关系会丢失。
- Public 端口是知道 URL 的人都能访问；测试完成后请停止 Codespace 或改回 Private。
