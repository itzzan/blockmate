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
        assertEquals(CommandName.SLEEP, Shortcuts.match("去睡觉！").orElseThrow());
        assertEquals(CommandName.EAT, Shortcuts.match("吃点东西。").orElseThrow());
    }

    @Test
    void 任务单17_进度问句_记住这里是xx_记住我们到xx了() {
        assertTrue(Shortcuts.isProgressQuestion("我们到哪了？"));
        assertTrue(Shortcuts.isProgressQuestion("现在到哪了"));
        assertTrue(Shortcuts.isProgressQuestion("进度"));
        assertTrue(Shortcuts.isProgressQuestion("我们现在什么进度？"));
        assertTrue(Shortcuts.isProgressQuestion("在哪个阶段"));
        assertTrue(!Shortcuts.isProgressQuestion("我们到哪了，还要多久"), "带别的内容交给模型");
        assertTrue(!Shortcuts.isProgressQuestion("村庄在哪"));
        assertEquals("村庄", Shortcuts.rememberHere("记住这里是村庄！").orElseThrow());
        assertEquals("矿洞", Shortcuts.rememberHere("记住这儿是矿洞").orElseThrow());
        assertEquals("家", Shortcuts.rememberHere("记住这里是家").orElseThrow(), "家也匹配，调用方先查固定短语表");
        assertTrue(Shortcuts.rememberHere("这里是村庄吗").isEmpty());
        assertTrue(Shortcuts.rememberHere("记住村庄在东边").isEmpty());
        assertEquals("铁器", Shortcuts.progressClaim("记住我们到铁器了").orElseThrow());
        assertEquals("下界", Shortcuts.progressClaim("记住我们已经到下界了。").orElseThrow());
        assertEquals("石器", Shortcuts.progressClaim("记住我们在石器阶段了").orElseThrow());
        assertEquals("钻石", Shortcuts.progressClaim("记住我们做完钻石了").orElseThrow());
        assertTrue(Shortcuts.progressClaim("我们到铁器了吗").isEmpty());
        assertTrue(Shortcuts.progressClaim("记住我们到哪了").isEmpty() || Shortcuts.progressClaim("记住我们到哪了").isPresent(),
                "这句不常见，交给调用方按阶段名查，查不到就说没听懂");
    }

    @Test
    void 任务单18_接受和拒绝() {
        assertTrue(Shortcuts.isAccept("好"));
        assertTrue(Shortcuts.isAccept("好的！"));
        assertTrue(Shortcuts.isAccept("去吧"));
        assertTrue(Shortcuts.isAccept("OK"));
        assertTrue(!Shortcuts.isAccept("好像不太对"));
        assertTrue(Shortcuts.isReject("不要"));
        assertTrue(Shortcuts.isReject("等会儿。"));
        assertTrue(Shortcuts.isReject("算了"));
        assertTrue(!Shortcuts.isReject("不知道"));
        assertTrue(!Shortcuts.isAccept("") && !Shortcuts.isReject(null));
    }
}
