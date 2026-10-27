package dev.blockmate.server.brain;

import dev.blockmate.protocol.CommandName;

import java.util.Map;
import java.util.Optional;

/**
 * 固定短语表（2026-10-22，6.5 第一批"等在这"10 次里 6 次模型只用文字答应没调工具）：
 * 主人的话去掉标点和空格后正好是表里的词，hub 直接下命令，不经过模型；其它说法仍由模型判断，闲聊不受影响。
 */
public final class Shortcuts {

    private static final Map<String, CommandName> TABLE = Map.ofEntries(
            Map.entry("停", CommandName.STOP),
            Map.entry("停下", CommandName.STOP),
            Map.entry("停一下", CommandName.STOP),
            Map.entry("别动", CommandName.STOP),
            Map.entry("站住", CommandName.STOP),
            Map.entry("别跟了", CommandName.STOP),
            Map.entry("等在这", CommandName.WAIT_HERE),
            Map.entry("等在这里", CommandName.WAIT_HERE),
            Map.entry("等在这儿", CommandName.WAIT_HERE),
            Map.entry("在这等", CommandName.WAIT_HERE),
            Map.entry("在这等我", CommandName.WAIT_HERE),
            Map.entry("在这儿等", CommandName.WAIT_HERE),
            Map.entry("等着", CommandName.WAIT_HERE),
            Map.entry("待着", CommandName.WAIT_HERE),
            Map.entry("跟我来", CommandName.FOLLOW),
            Map.entry("跟着我", CommandName.FOLLOW),
            Map.entry("跟上", CommandName.FOLLOW),
            Map.entry("跟我走", CommandName.FOLLOW),
            Map.entry("过来", CommandName.COME_HERE),
            Map.entry("来我这", CommandName.COME_HERE),
            Map.entry("来我这里", CommandName.COME_HERE),
            Map.entry("到我这来", CommandName.COME_HERE),
            // 任务单 13
            Map.entry("回家", CommandName.GO_HOME),
            Map.entry("回家吧", CommandName.GO_HOME),
            Map.entry("回家去", CommandName.GO_HOME),
            Map.entry("记住这里是家", CommandName.REMEMBER_PLACE),
            Map.entry("记住这是家", CommandName.REMEMBER_PLACE),
            Map.entry("这里是家", CommandName.REMEMBER_PLACE),
            Map.entry("这里就是家", CommandName.REMEMBER_PLACE),
            // 任务单 15
            Map.entry("去睡觉", CommandName.SLEEP),
            Map.entry("睡觉", CommandName.SLEEP),
            Map.entry("睡吧", CommandName.SLEEP),
            Map.entry("去睡吧", CommandName.SLEEP),
            Map.entry("吃点东西", CommandName.EAT),
            Map.entry("吃东西", CommandName.EAT),
            Map.entry("吃点什么", CommandName.EAT));

    private Shortcuts() {
    }

    /** 只保留汉字、字母和数字，其余（标点、空格、语气词后的符号）去掉。 */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        text.codePoints().filter(Character::isLetterOrDigit).forEach(sb::appendCodePoint);
        return sb.toString();
    }

    public static Optional<CommandName> match(String text) {
        return Optional.ofNullable(TABLE.get(normalize(text)));
    }

    // ---------- 任务单 17：进度和地标 ----------

    private static final java.util.Set<String> PROGRESS_QUESTIONS = java.util.Set.of(
            "我们到哪了", "我们到哪儿了", "现在到哪了", "现在到哪儿了", "到哪了", "到哪儿了", "我们现在到哪了", "我们现在到哪儿了",
            "进度", "进度呢", "什么进度", "现在什么进度", "我们什么进度", "我们现在什么进度", "走到哪了", "我们走到哪了",
            "现在在哪个阶段", "我们在哪个阶段", "在哪个阶段");

    // ---------- 任务单 18：对提议的接受 / 拒绝（只在上一条是它的提议、2 分钟内才用；平时这些词仍是闲聊） ----------

    private static final java.util.Set<String> ACCEPT = java.util.Set.of(
            "好", "好的", "好啊", "好呀", "好嘞", "行", "行啊", "可以", "可以啊", "去吧", "去", "去做吧", "做吧", "走吧", "嗯", "嗯嗯", "ok", "好好",
            "那就去吧", "去弄吧", "快去");
    private static final java.util.Set<String> REJECT = java.util.Set.of(
            "不", "不要", "不用", "不用了", "别", "别去", "别动", "等会", "等会儿", "等一下", "等等", "算了", "先不", "先不要", "先不用", "不行",
            "不去", "别提了", "以后再说", "不急", "再说吧");

    public static boolean isAccept(String text) {
        return ACCEPT.contains(normalize(text).toLowerCase());
    }

    public static boolean isReject(String text) {
        return REJECT.contains(normalize(text));
    }

    /** "我们到哪了 / 现在到哪了 / 进度"：后台按通关地图直接回，不经过模型。 */
    public static boolean isProgressQuestion(String text) {
        return PROGRESS_QUESTIONS.contains(normalize(text));
    }

    private static final java.util.regex.Pattern REMEMBER_HERE = java.util.regex.Pattern.compile("^记住(这里|这儿|这)是(.+)$");
    private static final java.util.regex.Pattern PROGRESS_CLAIM =
            java.util.regex.Pattern.compile("^记住我们(已经|现在)?(到|在|做完)(.+?)(阶段)?了$");

    /** "记住这里是村庄 / 矿洞 / 传送门"：返回主人说的那个词（"家"也会匹配，调用方先查 {@link #match}）。 */
    public static Optional<String> rememberHere(String text) {
        java.util.regex.Matcher m = REMEMBER_HERE.matcher(normalize(text));
        return m.matches() ? Optional.of(m.group(2)) : Optional.empty();
    }

    /** "记住我们到铁器了 / 记住我们已经到下界了"：返回阶段的叫法，由调用方对照通关地图。 */
    public static Optional<String> progressClaim(String text) {
        java.util.regex.Matcher m = PROGRESS_CLAIM.matcher(normalize(text));
        return m.matches() ? Optional.of(m.group(3)) : Optional.empty();
    }

    /** 固定短语对应的参数：目前只有"记住这里是家"要带 name=home。 */
    public static com.google.gson.JsonObject argsFor(CommandName name) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        if (name == CommandName.REMEMBER_PLACE) {
            o.addProperty("name", "home");
        }
        return o;
    }
}
