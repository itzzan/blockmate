package dev.blockmate.server.memory;

/** plans 表的一行（任务单 10）：一个计划的目标原话、步骤 JSON、结局、起止时间、来源消息。 */
public record StoredPlan(String id, String goal, String stepsJson, String outcome, long startedTs, long endedTs,
                         String sourceMessageId, String source) {

    /** 任务单 18 之前的写法；source 为 null / "owner" = 主人要的，"idle" = 它自己提议、主人点头的。 */
    public StoredPlan(String id, String goal, String stepsJson, String outcome, long startedTs, long endedTs,
                      String sourceMessageId) {
        this(id, goal, stepsJson, outcome, startedTs, endedTs, sourceMessageId, null);
    }
}
