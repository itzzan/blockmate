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
            Map.entry("到我这来", CommandName.COME_HERE));

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
}
