# Mac mini 侧的文件（手册 2.5、7.1）

等 Mac mini 到了，把这个目录里的文件拷过去，并把所有 `__USER__` 换成 Mac mini 上的用户名（`whoami`）：

```bash
sed -i '' "s#__USER__#$(whoami)#g" ~/companion/start-server.sh ~/Library/LaunchAgents/com.companion.*.plist ~/mcserver/backup.sh
```

| 文件 | 放到 Mac mini 的哪里 | 作用 |
| --- | --- | --- |
| start-server.sh | ~/companion/start-server.sh（chmod +x） | 等 Tailscale 就绪后用 Java 21 启动后台 |
| com.companion.server.plist | ~/Library/LaunchAgents/ | 后台开机自启、挂了自动重启（手册 7.1） |
| backup.sh | ~/mcserver/backup.sh（chmod +x） | 世界存档每日备份（手册 7.1 "强烈建议"） |
| com.companion.worldbackup.plist | ~/Library/LaunchAgents/ | 每天 4:30 跑 backup.sh，不 KeepAlive |
| rcon.py | ~/mcserver/rcon.py | 手册 2.6 的 mcrcon 替代品（Homebrew 里没有 mcrcon）；`python3 ~/mcserver/rcon.py "whitelist list"`，密码隐式输入或读环境变量 RCON_PASSWORD |

加载：`launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.companion.server.plist`（worldbackup 同理）。
手册 2.5 的 mcserver 启动脚本和 plist 按手册原文手写即可，这里不重复。
