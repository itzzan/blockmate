package dev.blockmate.protocol;

/** ui → hub："叫它上线 / 让它下线"，action 是 start 或 stop。 */
public record KeeperControl(String action) {
}
