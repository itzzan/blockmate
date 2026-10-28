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
                drops:
                  stone: cobblestone
                  minecraft:coal_ore: minecraft:coal
                """);
        Knowledge k = new Knowledge(dir);
        BlockRules r = k.blockRules();
        assertEquals(java.util.List.of("minecraft:oak_log", "minecraft:stone"), r.mineable());
        assertEquals(java.util.List.of("minecraft:crafting_table"), r.placeable());
        assertEquals("minecraft:cobblestone", r.dropOf("minecraft:stone"), "drops 两边都补命名空间");
        assertEquals("minecraft:oak_log", r.dropOf("minecraft:oak_log"));
        assertTrue(r.avoidFoodOrEmpty().isEmpty(), "没有 food.yaml 就没有不该吃的清单");
        Files.writeString(dir.resolve("food.yaml"), "avoid:\n  - rotten_flesh\n  - minecraft:spider_eye\n");
        assertEquals(java.util.List.of("minecraft:rotten_flesh", "minecraft:spider_eye"), k.blockRules().avoidFood());
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
        assertTrue(r.mineable().stream().noneMatch(s -> s.contains("cobble")), "圆石不在可挖清单（任务单 13 决定：墙多半是它）");
        assertEquals(29, r.mineable().size(), "任务单 18 加了 10 种矿");
        assertEquals(18, r.placeable().size());
        assertEquals("minecraft:cobblestone", r.dropOf("minecraft:stone"));
        assertEquals("minecraft:raw_iron", r.dropOf("minecraft:deepslate_iron_ore"));
        assertEquals("minecraft:diamond", r.dropOf("minecraft:deepslate_diamond_ore"), "任务单 18 加的矿");
        assertTrue(r.mineable().stream().noneMatch(s -> s.contains("obsidian")), "黑曜石故意不加（岩浆边）");
        assertEquals(18, r.dropsOrEmpty().size());
        assertTrue(r.avoidFood().contains("minecraft:rotten_flesh"), "仓库 food.yaml 里腐肉是不该吃的");
        assertTrue(r.needsPickaxe().contains("minecraft:stone"), "石头要镐");
        assertTrue(r.needsPickaxe().contains("minecraft:furnace"));
        assertEquals(19, r.needsPickaxe().size());
        assertEquals(6, r.avoidFood().size());
    }

    @Test
    void 任务单19_mobs_yaml() throws Exception {
        Knowledge k = new Knowledge(dir);
        assertTrue(k.mobRules().hostile().isEmpty(), "文件不存在就是空名单");
        Files.writeString(dir.resolve("mobs.yaml"), """
                hostile: [zombie, minecraft:skeleton, zombie]
                flee_only: [creeper]
                never: [player, Villager]
                hostile_at_night: [spider]
                hostile_if_attacked: [spider, enderman]
                """);
        dev.blockmate.protocol.MobRules m = k.mobRules();
        assertEquals(java.util.List.of("minecraft:zombie", "minecraft:skeleton"), m.hostile());
        assertEquals(java.util.List.of("minecraft:creeper"), m.fleeOnly());
        assertEquals(java.util.List.of("minecraft:player", "minecraft:villager"), m.never());
        assertEquals(java.util.List.of("minecraft:spider"), m.hostileAtNight());
        assertEquals(java.util.List.of("minecraft:spider", "minecraft:enderman"), m.hostileIfAttacked());
        assertTrue(Knowledge.parseMobs("hostile: 不是列表\n").hostile().isEmpty());
        assertTrue(Knowledge.empty().mobRules().hostile().isEmpty());

        Path repo = Path.of("..", "knowledge");
        if (Files.exists(repo.resolve("mobs.yaml"))) {
            dev.blockmate.protocol.MobRules r = new Knowledge(repo).mobRules();
            assertTrue(r.hostile().contains("minecraft:zombie"));
            assertTrue(r.hostile().contains("minecraft:blaze"));
            assertTrue(r.fleeOnly().contains("minecraft:creeper"));
            assertTrue(r.never().contains("minecraft:villager"));
            assertTrue(r.never().contains("minecraft:player"));
            assertTrue(r.never().contains("minecraft:cow"));
            assertTrue(r.hostileAtNight().contains("minecraft:spider"));
            assertTrue(r.hostileIfAttacked().contains("minecraft:enderman"));
            assertTrue(r.hostile().stream().noneMatch(s -> r.never().contains(s)), "一个 id 不能既能打又永远不碰");
            assertTrue(r.hostile().stream().noneMatch(s -> s.contains("creeper") || s.contains("enderman") || s.contains("spider") && !s.contains("cave")));
        }
    }
}
