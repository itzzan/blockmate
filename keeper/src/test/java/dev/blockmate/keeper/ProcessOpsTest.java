package dev.blockmate.keeper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 从 pgrep -fl 的输出里认出游戏进程（2026-10-22 补丁）。 */
class ProcessOpsTest {

    @Test
    void 只有命令本身是java的才算游戏() {
        assertTrue(ProcessOps.looksLikeGame(
                "/Users/zan/Library/Application Support/PrismLauncher/java/java-runtime-gamma/bin/java -Xmx4096m -cp x net.minecraft.client.main.Main"));
        assertTrue(ProcessOps.looksLikeGame("java -jar something.jar"));
        assertTrue(ProcessOps.looksLikeGame("/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home/bin/java -version"));
    }

    @Test
    void 提到了实例名和java的其他进程不算() {
        assertFalse(ProcessOps.looksLikeGame(
                "/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher --launch companion-vanilla-1.20.1 --profile Blockmate"));
        assertFalse(ProcessOps.looksLikeGame(
                "caffeinate -ims /Applications/Prism Launcher.app/Contents/MacOS/prismlauncher --launch companion-vanilla-1.20.1"));
        assertFalse(ProcessOps.looksLikeGame(
                "zsh -c pgrep -fl companion-vanilla-1.20.1 | grep bin/java"), "命令行里只是提到 bin/java 的 shell");
        assertFalse(ProcessOps.looksLikeGame("javac Foo.java"));
    }
}
