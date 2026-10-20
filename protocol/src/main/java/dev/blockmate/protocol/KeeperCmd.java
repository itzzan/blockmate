package dev.blockmate.protocol;

/** hub → keeper：action 是 start 或 stop（细则第 13 节）。 */
public record KeeperCmd(String action) {
    public static final String START = "start";
    public static final String STOP = "stop";
}
