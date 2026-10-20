package dev.blockmate.protocol;

/** hub → ui 的状态条内容（细则第 14 节）。伙伴离线时 companion.online=false，其余为 null。 */
public record Status(CompanionState companion, TaskInfo task, KeeperState keeper, ModelState model) {

    public record CompanionState(boolean online, Boolean inWorld, Double health, Integer food, String dim, Pos pos) {
        public static CompanionState offline() {
            return new CompanionState(false, null, null, null, null, null);
        }
    }

    public record KeeperState(boolean connected, Boolean running, String desired, Boolean gaveUp) {
        public static KeeperState disconnected() {
            return new KeeperState(false, null, null, null);
        }
    }

    public record ModelState(boolean ok) {
    }
}
