package dev.blockmate.protocol;

/** hub → mc（任务单 19）：规则变了（战斗开关、名单、白名单）不用重连，整份重发；模组收到只更新规则，不重发 snapshot / joined。 */
public record RulesUpdate(BlockRules rules, MobRules mobs, String combatMode) {
}
