package dev.blockmate.companion;

import java.util.Optional;

/** 只转发主人说的话（手册 6.3 第 2 条），纯逻辑。 */
public final class ChatFilter {

    private ChatFilter() {
    }

    /** 系统类聊天消息（原版格式 "<名字> 内容"）里，是主人说的就取出内容。 */
    public static Optional<String> ownerTextFromSystem(String text, String owner) {
        if (text == null || owner == null || owner.isEmpty()) {
            return Optional.empty();
        }
        String prefix = "<" + owner + "> ";
        if (text.startsWith(prefix)) {
            return Optional.of(text.substring(prefix.length()));
        }
        return Optional.empty();
    }

    /** 玩家类聊天消息：发送者名字等于主人名才要。 */
    public static boolean isOwner(String senderName, String owner) {
        return senderName != null && senderName.equals(owner);
    }
}
