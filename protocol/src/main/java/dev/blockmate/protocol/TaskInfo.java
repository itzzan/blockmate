package dev.blockmate.protocol;

/** 当前任务槽：commandId 对应那条 command 的信封 id；state 是 pending / running。 */
public record TaskInfo(String commandId, CommandName name, String state) {
}
