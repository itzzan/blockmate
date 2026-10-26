package dev.blockmate.server.brain;

import dev.blockmate.protocol.BlockRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务单 12：blocks.yaml 读入和整理。 */
class KnowledgeTest {

    @TempDir
    Path dir;

    @Test
    void 读blocks_yaml_补命名空间_去重() throws Exception {
        Files.writeString(dir.resolve("blocks.yaml"), """
                # 注释
                mineable:
                  - minecraft:oak_log
                  - Stone
                  - minecraft:oak_log
                never_touch:
                  - 箱子、门
                placeable:
                  - crafting_table
                """);
        Knowledge k = new Knowledge(dir);
        BlockRules r = k.blockRules();
        assertEquals(java.util.List.of("minecraft:oak_log", "minecraft:stone"), r.mineable());
        assertEquals(java.util.List.of("minecraft:crafting_table"), r.placeable());
        assertEquals("", k.planExamples(), "plan_examples.md 不存在就是空串");
    }

    @Test
    void 文件缺失或坏了都是空清单() throws Exception {
        Knowledge k = new Knowledge(dir);
        assertTrue(k.blockRules().mineable().isEmpty());
        assertTrue(k.blockRules().placeable().isEmpty());
        Files.writeString(dir.resolve("blocks.yaml"), "mineable: 这不是列表\n");
        assertTrue(k.blockRules().mineable().isEmpty(), "段不是列表当空");
        assertTrue(Knowledge.empty().blockRules().placeable().isEmpty());
    }

    @Test
    void 仓库里的blocks_yaml能读() {
        Path repo = Path.of("..", "knowledge");
        if (!Files.exists(repo.resolve("blocks.yaml"))) {
            return; // 不在仓库里跑测试时跳过
        }
        BlockRules r = new Knowledge(repo).blockRules();
        assertTrue(r.mineable().contains("minecraft:oak_log"));
        assertTrue(r.mineable().contains("minecraft:deepslate_iron_ore"));
        assertTrue(r.placeable().contains("minecraft:crafting_table"));
        assertTrue(r.placeable().contains("minecraft:red_bed"));
        assertTrue(r.mineable().stream().noneMatch(s -> s.contains("chest")), "箱子绝不能在可挖清单里");
        assertEquals(21, r.mineable().size());
        assertEquals(18, r.placeable().size());
    }
}
