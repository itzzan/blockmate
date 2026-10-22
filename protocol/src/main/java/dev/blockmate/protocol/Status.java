package dev.blockmate.protocol;

/** hub → ui 的状态条内容（细则第 14 节）。伙伴离线时 companion.online=false，其余为 null。 */
public record Status(CompanionState companion, TaskInfo task, KeeperState keeper, ModelState model) {

    /** inventorySummary 是 hub 用物品中文名生成的一句话（"橡木原木 8、面包 3"），给状态条直接显示。 */
    public record CompanionState(boolean online, Boolean inWorld, Double health, Integer food, String dim, Pos pos,
                                 String inventorySummary) {
        public static CompanionState offline() {
            return new CompanionState(false, null, null, null, null, null, null);
        }
    }

    /**
     * auto / ownerOnline 是"自动上下线"（手册 7.2 可选项）的状态：auto 为 null 表示后台没配 RCON、功能不可用；
     * ownerOnline 为 null 表示没在查（开关关着或 RCON 查不到）。
     */
    public record KeeperState(boolean connected, Boolean running, String desired, Boolean gaveUp,
                              Boolean auto, Boolean ownerOnline) {
        public static KeeperState disconnected(Boolean auto, Boolean ownerOnline) {
            return new KeeperState(false, null, null, null, auto, ownerOnline);
        }
    }

    public record ModelState(boolean ok) {
    }
}
