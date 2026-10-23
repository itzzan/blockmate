package dev.blockmate.companion;

import com.electronwill.nightconfig.core.file.FileWatcher;

/**
 * 关掉 Forge 的配置文件监视线程（2026-10-23）：Forge 47 自带的 night-config 3.6.4 在 macOS 上那个 "Thread-3"
 * FileWatcher 线程忙等（parkNanos 极短间隔），一个线程常年吃 35–40% CPU，是伙伴窗口在后台也 40% 的真正原因。
 * 代价：改 config/companion-client.toml 不再热生效，要重启游戏（我们本来就是重进世界才用新值）。
 */
final class ConfigWatcherFix {

    private static boolean done;

    private ConfigWatcherFix() {
    }

    static void stopOnce() {
        if (done) {
            return;
        }
        done = true;
        try {
            FileWatcher.defaultInstance().stop();
            CompanionMod.LOG.info(CompanionMod.TAG + "config file watcher stopped (night-config busy loop on macOS)");
        } catch (Throwable t) {
            CompanionMod.LOG.warn(CompanionMod.TAG + "could not stop config file watcher: {}", t.toString());
        }
    }
}
