package dev.blockmate.server.brain;

import dev.blockmate.protocol.CommandName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定短语表：只认整句正好是那几个词的，闲聊和带别的内容的句子都交给模型。 */
class ShortcutsTest {

    @Test
    void 整句匹配_忽略标点和空格() {
        assertEquals(Optional.of(CommandName.WAIT_HERE), Shortcuts.match("等在这"));
        assertEquals(Optional.of(CommandName.WAIT_HERE), Shortcuts.match("等在这！"));
        assertEquals(Optional.of(CommandName.WAIT_HERE), Shortcuts.match(" 等在这里。 "));
        assertEquals(Optional.of(CommandName.STOP), Shortcuts.match("停"));
        assertEquals(Optional.of(CommandName.STOP), Shortcuts.match("别动～"));
        assertEquals(Optional.of(CommandName.FOLLOW), Shortcuts.match("跟我来"));
        assertEquals(Optional.of(CommandName.COME_HERE), Shortcuts.match("过来，"));
    }

    @Test
    void 带别的内容或闲聊不匹配() {
        assertTrue(Shortcuts.match("等在这，我去拿点东西").isEmpty());
        assertTrue(Shortcuts.match("你等一下再说").isEmpty());
        assertTrue(Shortcuts.match("今天天气不错").isEmpty());
        assertTrue(Shortcuts.match("过来看看这个").isEmpty());
        assertTrue(Shortcuts.match("").isEmpty());
        assertTrue(Shortcuts.match(null).isEmpty());
    }

    @Test
    void 回家和记住这里是家() {
        assertEquals(CommandName.GO_HOME, Shortcuts.match("回家！").orElseThrow());
        assertEquals(CommandName.GO_HOME, Shortcuts.match("回家吧").orElseThrow());
        assertEquals(CommandName.REMEMBER_PLACE, Shortcuts.match("记住这里是家。").orElseThrow());
        assertEquals("home", Shortcuts.argsFor(CommandName.REMEMBER_PLACE).get("name").getAsString());
        assertEquals(0, Shortcuts.argsFor(CommandName.GO_HOME).size());
        assertTrue(Shortcuts.match("回家路上顺便砍树").isEmpty(), "带别的内容的交给模型");
    }
}
