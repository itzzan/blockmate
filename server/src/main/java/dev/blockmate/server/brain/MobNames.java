package dev.blockmate.server.brain;

import java.util.Map;

/** 生物 id → 中文名（任务单 19，网页提示"它和僵尸打了一架"用）；没映射的去掉前缀显示。 */
public final class MobNames {

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("minecraft:zombie", "僵尸"), Map.entry("minecraft:husk", "尸壳"), Map.entry("minecraft:drowned", "溺尸"),
            Map.entry("minecraft:zombie_villager", "僵尸村民"), Map.entry("minecraft:skeleton", "骷髅"), Map.entry("minecraft:stray", "流浪者"),
            Map.entry("minecraft:wither_skeleton", "凋灵骷髅"), Map.entry("minecraft:spider", "蜘蛛"), Map.entry("minecraft:cave_spider", "洞穴蜘蛛"),
            Map.entry("minecraft:creeper", "苦力怕"), Map.entry("minecraft:witch", "女巫"), Map.entry("minecraft:slime", "史莱姆"),
            Map.entry("minecraft:magma_cube", "岩浆怪"), Map.entry("minecraft:blaze", "烈焰人"), Map.entry("minecraft:ghast", "恶魂"),
            Map.entry("minecraft:enderman", "末影人"), Map.entry("minecraft:endermite", "末影螨"), Map.entry("minecraft:silverfish", "蠹虫"),
            Map.entry("minecraft:zombified_piglin", "僵尸猪灵"), Map.entry("minecraft:piglin", "猪灵"), Map.entry("minecraft:piglin_brute", "猪灵蛮兵"),
            Map.entry("minecraft:hoglin", "疣猪兽"), Map.entry("minecraft:zoglin", "僵尸疣猪兽"), Map.entry("minecraft:phantom", "幻翼"),
            Map.entry("minecraft:pillager", "掠夺者"), Map.entry("minecraft:vindicator", "卫道士"), Map.entry("minecraft:evoker", "唤魔者"),
            Map.entry("minecraft:vex", "恼鬼"), Map.entry("minecraft:ravager", "劫掠兽"), Map.entry("minecraft:guardian", "守卫者"),
            Map.entry("minecraft:elder_guardian", "远古守卫者"), Map.entry("minecraft:shulker", "潜影贝"), Map.entry("minecraft:warden", "监守者"),
            Map.entry("minecraft:wither", "凋灵"), Map.entry("minecraft:ender_dragon", "末影龙"), Map.entry("minecraft:villager", "村民"),
            Map.entry("minecraft:iron_golem", "铁傀儡"), Map.entry("minecraft:player", "玩家"));

    private MobNames() {
    }

    public static String name(String id) {
        if (id == null) {
            return "";
        }
        String n = NAMES.get(id);
        if (n != null) {
            return n;
        }
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
