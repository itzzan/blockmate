package dev.blockmate.protocol;

import com.google.gson.JsonArray;

/**
 * hub 对 hello 的回复（细则第 4 节）。
 * ok=false 时 error 说明原因，随后 hub 以 4401 关闭连接。
 * 对 ui 额外带 status 和 history（最近 N 条消息，原始 JSON 数组）；对 keeper 额外带 desired；
 * 对 mc 额外带 rules（任务单 12：能挖、能放的白名单）。
 */
public record HelloOk(boolean ok, Long serverTime, Integer protocolV, String desired, Status status,
                      JsonArray history, String error, BlockRules rules, MobRules mobs, String combatMode) {

    /** 任务单 19 之前的写法（没有怪物名单和战斗开关）。 */
    public HelloOk(boolean ok, Long serverTime, Integer protocolV, String desired, Status status,
                   JsonArray history, String error, BlockRules rules) {
        this(ok, serverTime, protocolV, desired, status, history, error, rules, null, null);
    }

    public static HelloOk ok(long serverTime) {
        return new HelloOk(true, serverTime, ProtocolCodec.VERSION, null, null, null, null, null);
    }

    /** 回模组：带白名单。 */
    public static HelloOk mc(long serverTime, BlockRules rules) {
        return new HelloOk(true, serverTime, ProtocolCodec.VERSION, null, null, null, null, rules);
    }

    /** 回模组（任务单 19）：白名单 + 怪物名单 + 战斗开关。 */
    public static HelloOk mc(long serverTime, BlockRules rules, MobRules mobs, String combatMode) {
        return new HelloOk(true, serverTime, ProtocolCodec.VERSION, null, null, null, null, rules, mobs, combatMode);
    }

    public static HelloOk rejected(String error) {
        return new HelloOk(false, null, ProtocolCodec.VERSION, null, null, null, error, null);
    }
}
