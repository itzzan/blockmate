# mod · Blockmate 控制模组

Forge 1.20.1（47.4.10）的纯客户端模组，独立的 ForgeGradle 工程，不并入根工程。

- 编译：`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home ./gradlew build`（第一次要下载工具链和反编译 Minecraft，十几分钟）
- 前置：`libs/baritone-api-forge-1.10.5.jar`（见 libs/README.md）；根工程先 `./gradlew :protocol:publishToMavenLocal`
- 产物：`build/libs/companion-0.1.0-all.jar`（含 protocol 和 Java-WebSocket），放进 Prism 实例的 `minecraft/mods/`，和 Baritone 的 jar 并排
- 配置：游戏第一次启动后在实例的 `minecraft/config/companion-client.toml`
- 不用 MDK 自带的 runClient，用 Prism 实例测试（手册 6.1）
