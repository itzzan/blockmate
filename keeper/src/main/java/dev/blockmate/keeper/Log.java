package dev.blockmate.keeper;

import java.time.LocalTime;

/** 最简日志：一行一条，带时间，不含任何聊天内容（keeper 本来也碰不到）。 */
public final class Log {

    private Log() {
    }

    public static void info(String msg) {
        System.out.println("[keeper " + LocalTime.now().withNano(0) + "] " + msg);
        System.out.flush();
    }

    public static void warn(String msg) {
        System.out.println("[keeper " + LocalTime.now().withNano(0) + "] WARN " + msg);
        System.out.flush();
    }
}
