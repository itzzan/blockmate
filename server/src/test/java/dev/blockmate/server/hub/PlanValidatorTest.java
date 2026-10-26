package dev.blockmate.server.hub;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blockmate.protocol.BlockRules;
import dev.blockmate.protocol.CommandName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 模型给的计划要被校验（任务单 11）。 */
class PlanValidatorTest {

    private static final BlockRules RULES = new BlockRules(List.of("minecraft:oak_log", "minecraft:cobblestone"),
            List.of("minecraft:crafting_table", "minecraft:torch", "minecraft:red_bed"));

    private static JsonObject j(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void 合法的三步计划() {
        PlanValidator.Validated v = PlanValidator.validate(j("""
                {"goal":"去弄 20 根木头回来","steps":[
                  {"command":"mine","args":{"block":"oak_log","count":20},"why":"砍"},
                  {"command":"come_here","args":{}},
                  {"command":"deliver","args":{"item":"minecraft:oak_log","count":20}}]}"""), RULES);
        assertTrue(v.ok(), v.errors().toString());
        assertEquals(3, v.steps().size());
        assertEquals(CommandName.MINE, v.steps().get(0).command());
        assertEquals("minecraft:oak_log", v.steps().get(0).args().get("block").getAsString(), "没写命名空间要补上");
        assertEquals(2, v.steps().get(2).index());
    }

    @Test
    void 不合法的要列出原因() {
        PlanValidator.Validated v = PlanValidator.validate(j("""
                {"goal":"x","steps":[
                  {"command":"stop","args":{}},
                  {"command":"mine","args":{"block":"oak_log"}},
                  {"command":"place","args":{"block":"minecraft:furnace"}},
                  {"command":"goto","args":{}},
                  {"command":"mine","args":{"block":"minecraft:chest","count":1}},
                  {"command":"fly","args":{}}]}"""), RULES);
        assertFalse(v.ok());
        String all = String.join("；", v.errors());
        assertTrue(all.contains("minecraft:chest 不在允许挖的清单里"), all);
        assertTrue(all.contains("stop"), all);
        assertTrue(all.contains("缺 count"), all);
        assertTrue(all.contains("不能放 minecraft:furnace"), all);
        assertTrue(all.contains("坐标"), all);
        assertTrue(all.contains("fly"), all);
    }

    @Test
    void 步骤数上限和空计划() {
        StringBuilder sb = new StringBuilder("{\"goal\":\"x\",\"steps\":[");
        for (int i = 0; i < 9; i++) {
            sb.append(i > 0 ? "," : "").append("{\"command\":\"come_here\",\"args\":{}}");
        }
        sb.append("]}");
        PlanValidator.Validated v = PlanValidator.validate(j(sb.toString()), RULES);
        assertFalse(v.ok());
        assertTrue(v.errors().get(0).contains("最多 8 步"), v.errors().toString());
        assertFalse(PlanValidator.validate(j("{\"goal\":\"x\",\"steps\":[]}"), RULES).ok());
        assertFalse(PlanValidator.validate(new JsonObject(), RULES).ok());
    }

    @Test
    void 空清单什么都不许挖() {
        PlanValidator.Validated v = PlanValidator.validate(j("""
                {"goal":"x","steps":[{"command":"mine","args":{"block":"oak_log","count":1}}]}"""), BlockRules.empty());
        assertFalse(v.ok());
        assertTrue(v.errors().get(0).contains("不在允许挖的清单里"), v.errors().toString());
    }
}
