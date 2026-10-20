// Blockmate 后台侧的 Gradle 多模块工程（Java 21）。
// 模组 mod/ 是独立的 ForgeGradle 工程（Java 17），不包含在这里。
rootProject.name = "blockmate"
include("protocol")
include("mock-executor")
