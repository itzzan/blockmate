package dev.blockmate.server.brain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 任务单 16：通关地图的解析和"在哪个阶段、缺什么"。 */
class ProgressionTest {

    private static final String SMALL = """
            item_groups:
              pick_wood_plus:
                name: 镐
                items: [wooden_pickaxe, stone_pickaxe]
              food: [bread, minecraft:cooked_beef]
            fact_names:
              visited_nether: 去过下界
            stages:
              - id: start
                name: 开局
                needs: [{group: pick_wood_plus}]
                actions: [mine oak_log 8]
                say: 先做木镐。
              - id: stone
                name: 石器
                needs: [{item: stone_pickaxe}, {item: furnace, count: 1}]
                actions: [craft furnace 1]
                say: 做熔炉。
              - id: food
                name: 吃饱
                needs: [{group: food, count: 8}]
                actions: [craft bread 3]
                say: 弄吃的。
              - id: nether
                name: 下界
                needs: [{group: food, count: 8}]
                facts: [visited_nether]
                actions: [build_portal]
                say: 进下界。
            """;

    private static final ItemNames NAMES = new ItemNames(Map.of("minecraft:furnace", "熔炉", "minecraft:stone_pickaxe", "石镐"));

    @Test
    void 空手是开局_缺镐() {
        Progression p = Progression.parse(SMALL);
        Progression.Assessment a = p.assess(Map.of(), Set.of(), Set.of());
        assertEquals("start", a.current().id());
        assertTrue(a.done().isEmpty());
        assertEquals(1, a.missingItems().size());
        assertEquals("pick_wood_plus", a.missingItems().get(0).need().group());
        assertEquals(0, a.missingItems().get(0).have());
        assertEquals("现在在「开局」阶段（4 个阶段里的第 1 个），缺：镐 0/1。先做木镐。", p.describe(a, NAMES));
    }

    @Test
    void 有石镐有熔炉_石器做完了该吃饱() {
        Progression p = Progression.parse(SMALL);
        Progression.Assessment a = p.assess(Map.of("stone_pickaxe", 1, "minecraft:furnace", 1, "bread", 2), Set.of(), Set.of());
        assertEquals("food", a.current().id());
        assertEquals(List.of("start", "stone"), a.done().stream().map(Progression.Stage::id).toList());
        assertEquals(1, a.missingItems().size());
        assertEquals(2, a.missingItems().get(0).have(), "组按组内物品加总");
        assertEquals("现在在「吃饱」阶段（4 个阶段里的第 3 个），缺：food 2/8。弄吃的。", p.describe(a, NAMES),
                "没写 name 的组就显示组 id");
    }

    @Test
    void 后面阶段做完了前面就算做完_缺事实单独列() {
        Progression p = Progression.parse(SMALL);
        // 没有熔炉，但吃的够 8：吃饱阶段做完 → 石器也算做完
        Progression.Assessment a = p.assess(Map.of("bread", 5, "cooked_beef", 3), Set.of(), Set.of());
        assertEquals("nether", a.current().id());
        assertEquals(List.of("start", "stone", "food"), a.done().stream().map(Progression.Stage::id).toList());
        assertTrue(a.missingItems().isEmpty());
        assertEquals(List.of("visited_nether"), a.missingFacts());
        assertEquals("现在在「下界」阶段（4 个阶段里的第 4 个），还没做到：去过下界。进下界。", p.describe(a, NAMES));
    }

    @Test
    void 记过的阶段不退回() {
        Progression p = Progression.parse(SMALL);
        Progression.Assessment a = p.assess(Map.of(), Set.of(), Set.of("stone"));
        assertEquals("food", a.current().id(), "记过石器就从吃饱开始算，开局也跟着算做完");
        assertEquals(2, a.done().size());
    }

    @Test
    void 全部做完() {
        Progression p = Progression.parse(SMALL);
        Progression.Assessment a = p.assess(Map.of("bread", 8), Set.of("visited_nether"), Set.of());
        assertTrue(a.allDone());
        assertNull(a.current());
        assertEquals(4, a.done().size());
        assertEquals("通关路上的 4 个阶段都做完了，龙打过了。", p.describe(a, NAMES));
    }

    @Test
    void 空地图和坏地图() {
        assertTrue(Progression.empty().isEmpty());
        assertTrue(Progression.parse("").isEmpty());
        assertTrue(Progression.parse("stages: 这不是列表\n").isEmpty());
        assertTrue(Progression.parse("- 顶层是列表\n").isEmpty());
        Progression.Assessment a = Progression.empty().assess(Map.of("bread", 1), Set.of(), Set.of());
        assertTrue(a.allDone(), "没有阶段时 current 为 null，但 describe 会说没有地图");
        assertTrue(Progression.empty().describe(a, NAMES).contains("通关地图"));
        assertThrows(IllegalArgumentException.class, () -> Progression.parse("""
                stages:
                  - id: a
                    needs: [{group: 不存在的组}]
                """), "引用不存在的组整份文件不用");
        assertThrows(IllegalArgumentException.class, () -> Progression.parse("stages:\n  - name: 没有id\n"));
        assertThrows(IllegalArgumentException.class, () -> Progression.parse("stages:\n  - id: a\n  - id: a\n"));
        assertThrows(IllegalArgumentException.class, () -> Progression.parse("stages:\n  - id: a\n    needs: [{count: 3}]\n"));
    }

    @Test
    void 按修改时间热重载(@TempDir Path dir) throws Exception {
        Knowledge k = new Knowledge(dir);
        assertTrue(k.progression().isEmpty(), "文件不存在就是空地图");
        Path p = dir.resolve("progression.yaml");
        Files.writeString(p, "stages:\n  - id: a\n    name: 甲\n");
        assertEquals(1, k.progression().stages().size());
        Files.writeString(p, "stages:\n  - id: a\n    name: 甲\n  - id: b\n    name: 乙\n");
        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertEquals(2, k.progression().stages().size(), "改了文件再问就是新的");
        Files.writeString(p, "stages:\n  - id: a\n    needs: [{group: 没有}]\n");
        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() + 10000));
        assertTrue(k.progression().isEmpty(), "坏了按没有地图处理");
        assertTrue(Knowledge.empty().progression().isEmpty());
    }

    @Test
    void 仓库里的progression_yaml能读_三组场景和老玩家判断一致() {
        Path repo = Path.of("..", "knowledge");
        if (!Files.exists(repo.resolve("progression.yaml"))) {
            return; // 不在仓库里跑测试时跳过
        }
        Progression p = new Knowledge(repo).progression();
        assertEquals(List.of("start", "stone", "food", "iron", "diamond", "nether_prep", "nether", "end_prep", "end"),
                p.stages().stream().map(Progression.Stage::id).toList());
        for (Progression.Stage s : p.stages()) {
            assertFalse(s.needs().isEmpty(), s.id() + " 要有 needs");
            assertFalse(s.actions().isEmpty(), s.id() + " 要有 actions");
            assertFalse(s.say().isEmpty(), s.id() + " 要有 say");
            assertFalse(s.name().isEmpty() || s.name().equals(s.id()), s.id() + " 要有中文名");
        }
        assertEquals(8, p.groups().get("logs").size());
        assertEquals(16, p.groups().get("bed").size());
        assertTrue(p.groups().get("pickaxe_stone_plus").contains("minecraft:netherite_pickaxe"));
        ItemNames names = ItemNames.load();

        // 1. 开局空手
        Progression.Assessment a1 = p.assess(Map.of(), Set.of(), Set.of());
        assertEquals("start", a1.current().id());
        assertEquals("镐（木镐或更好）", p.label(a1.missingItems().get(0).need(), names));

        // 2. 有石镐有熔炉没铁：石器做完，该吃饱
        Progression.Assessment a2 = p.assess(Map.of("minecraft:stone_pickaxe", 1, "minecraft:furnace", 1,
                "minecraft:cobblestone", 30), Set.of(), Set.of());
        assertEquals("food", a2.current().id());
        assertEquals(List.of("start", "stone"), a2.done().stream().map(Progression.Stage::id).toList());
        assertTrue(p.describe(a2, names).contains("吃的（熟食、面包这类） 0/8"), p.describe(a2, names));

        // 2b. 吃的够了、有铁镐铁剑但没盾没桶：在铁器阶段，缺盾和桶
        Progression.Assessment a2b = p.assess(Map.of("minecraft:iron_pickaxe", 1, "minecraft:iron_sword", 1,
                "minecraft:bread", 10, "minecraft:furnace", 1), Set.of(), Set.of());
        assertEquals("iron", a2b.current().id());
        assertEquals(List.of("minecraft:shield", "minecraft:bucket"),
                a2b.missingItems().stream().map(m -> m.need().isGroup() ? "minecraft:bucket" : m.need().item()).toList());
        assertTrue(p.describe(a2b, names).contains("盾牌 0/1、桶 0/1"), p.describe(a2b, names));

        // 3. 有 10 黑曜石有打火石（装备齐）：下界准备做完，在下界阶段，缺的是事实
        Map<String, Integer> kit = Map.of("minecraft:obsidian", 10, "minecraft:flint_and_steel", 1,
                "minecraft:diamond_pickaxe", 1, "minecraft:iron_sword", 1, "minecraft:shield", 1,
                "minecraft:water_bucket", 1, "minecraft:cooked_beef", 12);
        Progression.Assessment a3 = p.assess(kit, Set.of(), Set.of());
        assertEquals("nether", a3.current().id());
        assertEquals(6, a3.done().size());
        assertTrue(a3.missingItems().isEmpty());
        assertEquals(List.of("portal_overworld", "visited_nether", "fortress"), a3.missingFacts());
        String d3 = p.describe(a3, names);
        assertTrue(d3.contains("还没做到：主世界这边的传送门点好了、去过下界、找到下界要塞"), d3);

        // 3b. 同样的装备但没吃的：卡在下界准备，缺吃的
        Progression.Assessment a3b = p.assess(Map.of("minecraft:obsidian", 10, "minecraft:flint_and_steel", 1,
                "minecraft:diamond_pickaxe", 1, "minecraft:iron_sword", 1, "minecraft:shield", 1,
                "minecraft:bucket", 1), Set.of(), Set.of());
        assertEquals("nether_prep", a3b.current().id());
        assertEquals(1, a3b.missingItems().size());
        assertEquals("cooked_food", a3b.missingItems().get(0).need().group());

        // 4. 记过 nether，背包空：在末地准备，缺 12 个眼
        Progression.Assessment a4 = p.assess(Map.of(), Set.of(), Set.of("nether"));
        assertEquals("end_prep", a4.current().id());
        assertTrue(p.describe(a4, names).contains("末影之眼 0/12"), p.describe(a4, names));

        // 5. 打过龙：全部做完
        Progression.Assessment a5 = p.assess(Map.of("minecraft:bow", 1, "minecraft:arrow", 20, "minecraft:bread", 16),
                Set.of("stronghold", "end_portal", "visited_end", "dragon_killed"), Set.of("end_prep"));
        assertTrue(a5.allDone());
    }
}
