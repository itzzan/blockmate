# Blockmate · 自托管 AI 伙伴

方块世界里的搭子。用中文和我聊天，并以独立玩家身份在我的 Minecraft 1.20.1 原版服务器里跟随、停下、过来、捡起、交付物品。

GitHub：itzzan/blockmate。本地文件夹也叫 `blockmate`。手册（docs/guide.md）里写的 `~/companion` 在这台机器上就是这个文件夹，没有做软链接，照手册敲命令时把路径换成它。

## 目录

| 目录 | 是什么 | 运行在 |
| --- | --- | --- |
| `protocol/` | 后台和模组之间 WebSocket 消息的共同定义（Java 17 字节码，两边都能用） | 库 |
| `server/` | 后台：Hub / Brain / Gateway / Memory（Java 21） | Mac mini |
| `mock-executor/` | 假执行器，假装是游戏客户端，用来不开游戏测后台 | MacBook（开发时） |
| `keeper/` | 守护进程，负责开关伙伴的游戏客户端（Java 21） | MacBook |
| `mod/` | 控制模组：link / observer / executor（Forge 1.20.1，Java 17） | MacBook 的游戏客户端里 |
| `ui/` | 聊天网页，静态文件，由后台直接提供 | 浏览器 |
| `ops/` | 脚本、launchd 配置、Modelfile | 两台 Mac |
| `docs/` | 设计文档和记录 | |

## 文档

- `docs/guide.md`：《自托管 AI 伙伴 · 小白版从 0 到 1 实施手册》。**唯一的规范**，操作主线，按步骤做。
- 小本子（每步的结果、报错、地址、账号等个人记录）放在仓库外，不入库。
- `ops/*.example.*` 是模板；填了真实邮箱、地址、令牌的文件一律用 `.local.` 后缀，已被 .gitignore 排除。
- 协议、消息格式、状态机、数据库表的细则，手册只给了名字和约束。第 5 步动手前先据此另写一份 `docs/handbook.md`，经用户审核后作为实现规范。

## 约束（给 AI 编程助手）

- 后台（server、protocol、mock-executor、keeper）用 Java 21 + Gradle，打包成单个可运行 jar；模组（mod）用 Java 17 + ForgeGradle。
- 大模型是 MacBook 上的 Ollama，地址和模型名来自 `config.yaml`，模型名固定为 `companion-local`。不接任何云服务。
- 命令只来自网页（`/ws/ui`）；游戏内聊天不是命令渠道。同一时刻只有一个进行中的任务，新任务替换旧任务时旧任务要收到自己的 cancelled 结果。
- 日志 INFO 级别不得包含聊天内容原文，只记事件和长度。密码和令牌只从 `config.yaml` 读，不进 git。
- 每个模块要有能用 `./gradlew test` 跑的单元测试。网页和所有面向用户的文案用中文。
- 手册没写清、或两处说法不一致的地方，先问用户，不自己拍板。
