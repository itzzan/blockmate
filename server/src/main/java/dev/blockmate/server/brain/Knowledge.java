package dev.blockmate.server.brain;

import dev.blockmate.protocol.BlockRules;
import dev.blockmate.protocol.MobRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 知识层的文件（任务单 11）：knowledge/ 目录下的纯文本，和 persona.md 一样按修改时间热重载。
 * plan_examples.md（常见任务怎么拆）、blocks.yaml（任务单 12/13）、food.yaml（任务单 15）、progression.yaml（任务单 16，通关地图）。
 */
public final class Knowledge {

    private static final Logger log = LoggerFactory.getLogger(Knowledge.class);

    private final Path dir;
    private String examples = "";
    private FileTime examplesMtime;
    private boolean warned;
    private BlockRules rules = BlockRules.empty();
    private FileTime rulesMtime;
    private boolean rulesWarned;
    private List<String> avoidFood = List.of();
    private FileTime foodMtime;
    private Progression progression = Progression.empty();
    private FileTime progressionMtime;
    private boolean progressionWarned;
    private MobRules mobs = MobRules.empty();
    private FileTime mobsMtime;
    private boolean mobsWarned;

    public Knowledge(Path dir) {
        this.dir = dir;
    }

    /** 空的知识库（测试用）。 */
    public static Knowledge empty() {
        return new Knowledge(null);
    }

    public synchronized String planExamples() {
        if (dir == null) {
            return "";
        }
        Path p = dir.resolve("plan_examples.md");
        try {
            if (Files.exists(p)) {
                FileTime mtime = Files.getLastModifiedTime(p);
                if (examplesMtime == null || !mtime.equals(examplesMtime)) {
                    examples = Files.readString(p, StandardCharsets.UTF_8);
                    examplesMtime = mtime;
                    log.info("knowledge loaded file=plan_examples.md len={}", examples.length());
                }
                return examples;
            }
        } catch (IOException e) {
            log.warn("knowledge read failed: {}", e.getMessage());
        }
        if (!warned) {
            log.warn("knowledge file missing: {}（没有拆步骤的例子，模型只能自己猜）", p);
            warned = true;
        }
        return "";
    }

    /**
     * blocks.yaml 的 mineable / placeable / drops（任务单 12、13）加 food.yaml 的 avoid（任务单 15）。
     * blocks.yaml 缺失或坏了返回空清单 = 什么都不许挖、不许放；food.yaml 缺失只是没有"不该吃"的清单。
     * id 没写命名空间自动补 minecraft:，统一小写。
     */
    public synchronized BlockRules blockRules() {
        if (dir == null) {
            return rules;
        }
        BlockRules base = BlockRules.empty();
        Path p = dir.resolve("blocks.yaml");
        boolean haveBlocks = false;
        try {
            if (Files.exists(p)) {
                FileTime mtime = Files.getLastModifiedTime(p);
                if (rulesMtime == null || !mtime.equals(rulesMtime)) {
                    rules = parseRules(Files.readString(p, StandardCharsets.UTF_8));
                    rulesMtime = mtime;
                    log.info("knowledge loaded file=blocks.yaml mineable={} placeable={} drops={}", rules.mineable().size(),
                            rules.placeable().size(), rules.dropsOrEmpty().size());
                }
                base = rules;
                haveBlocks = true;
            }
        } catch (IOException | RuntimeException e) {
            log.warn("knowledge blocks.yaml read failed: {}（按空清单处理，什么都不许挖）", e.getMessage());
        }
        if (!haveBlocks && !rulesWarned) {
            log.warn("knowledge file missing: {}（没有白名单，伙伴什么都不许挖、不许放）", p);
            rulesWarned = true;
        }
        return new BlockRules(base.mineable(), base.placeable(), base.dropsOrEmpty(), avoidFood(), base.needsPickaxeOrEmpty());
    }

    /** food.yaml 的 avoid 段：不该吃的食物。缺失 = 空。 */
    private List<String> avoidFood() {
        Path p = dir.resolve("food.yaml");
        try {
            if (Files.exists(p)) {
                FileTime mtime = Files.getLastModifiedTime(p);
                if (foodMtime == null || !mtime.equals(foodMtime)) {
                    Object loaded = new Yaml().load(Files.readString(p, StandardCharsets.UTF_8));
                    avoidFood = loaded instanceof Map<?, ?> m ? idList(m.get("avoid")) : List.of();
                    foodMtime = mtime;
                    log.info("knowledge loaded file=food.yaml avoid={}", avoidFood.size());
                }
                return avoidFood;
            }
        } catch (IOException | RuntimeException e) {
            log.warn("knowledge food.yaml read failed: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * progression.yaml 的通关地图（任务单 16）。文件缺失或坏了 = 空地图（Progression.empty()，describe 会说"没有通关地图"）；
     * 坏文件只在它再次被改动时重读，不会每次都报错。
     */
    public synchronized Progression progression() {
        if (dir == null) {
            return progression;
        }
        Path p = dir.resolve("progression.yaml");
        if (!Files.exists(p)) {
            if (!progressionWarned) {
                log.warn("knowledge file missing: {}（没有通关地图，说不清走到哪个阶段）", p);
                progressionWarned = true;
            }
            return Progression.empty();
        }
        FileTime mtime;
        try {
            mtime = Files.getLastModifiedTime(p);
        } catch (IOException e) {
            log.warn("knowledge progression.yaml stat failed: {}", e.getMessage());
            return progression;
        }
        if (progressionMtime == null || !mtime.equals(progressionMtime)) {
            progressionMtime = mtime;
            try {
                progression = Progression.parse(Files.readString(p, StandardCharsets.UTF_8));
                log.info("knowledge loaded file=progression.yaml stages={} groups={}", progression.stages().size(),
                        progression.groups().size());
            } catch (IOException | RuntimeException e) {
                progression = Progression.empty();
                log.warn("knowledge progression.yaml read failed: {}（按没有通关地图处理）", e.getMessage());
            }
        }
        return progression;
    }

    /**
     * mobs.yaml 的怪物名单（任务单 19）：hostile / flee_only / never / hostile_at_night / hostile_if_attacked。
     * 缺失或坏了 = 空名单 = 什么都不打（安全默认），warn 一次。按修改时间热重载。
     */
    public synchronized MobRules mobRules() {
        if (dir == null) {
            return mobs;
        }
        Path p = dir.resolve("mobs.yaml");
        if (!Files.exists(p)) {
            if (!mobsWarned) {
                log.warn("knowledge file missing: {}（没有怪物名单，伙伴什么都不打）", p);
                mobsWarned = true;
            }
            return MobRules.empty();
        }
        FileTime mtime;
        try {
            mtime = Files.getLastModifiedTime(p);
        } catch (IOException e) {
            return mobs;
        }
        if (mobsMtime == null || !mtime.equals(mobsMtime)) {
            mobsMtime = mtime;
            try {
                mobs = parseMobs(Files.readString(p, StandardCharsets.UTF_8));
                log.info("knowledge loaded file=mobs.yaml hostile={} flee_only={} never={}", mobs.hostile().size(),
                        mobs.fleeOnly().size(), mobs.never().size());
            } catch (IOException | RuntimeException e) {
                mobs = MobRules.empty();
                log.warn("knowledge mobs.yaml read failed: {}（按空名单处理，什么都不打）", e.getMessage());
            }
        }
        return mobs;
    }

    static MobRules parseMobs(String yamlText) {
        Object loaded = new Yaml().load(yamlText);
        if (!(loaded instanceof Map<?, ?> m)) {
            return MobRules.empty();
        }
        return new MobRules(idList(m.get("hostile")), idList(m.get("flee_only")), idList(m.get("never")),
                idList(m.get("hostile_at_night")), idList(m.get("hostile_if_attacked")));
    }

    static BlockRules parseRules(String yamlText) {
        Object loaded = new Yaml().load(yamlText);
        if (!(loaded instanceof Map<?, ?> m)) {
            return BlockRules.empty();
        }
        return new BlockRules(idList(m.get("mineable")), idList(m.get("placeable")), dropMap(m.get("drops")), List.of(),
                idList(m.get("needs_pickaxe")));
    }

    private static Map<String, String> dropMap(Object o) {
        if (!(o instanceof Map<?, ?> m)) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                String k = normalizeId(e.getKey().toString());
                String v = normalizeId(e.getValue().toString());
                if (!k.isEmpty() && !v.isEmpty()) {
                    out.put(k, v);
                }
            }
        }
        return Map.copyOf(out);
    }

    private static List<String> idList(Object o) {
        if (!(o instanceof List<?> l)) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Object item : l) {
            if (item != null) {
                String id = normalizeId(item.toString());
                if (!id.isEmpty()) {
                    out.add(id);
                }
            }
        }
        return List.copyOf(out);
    }

    /** 统一成 minecraft:xxx 小写；空串原样返回。 */
    public static String normalizeId(String raw) {
        String v = raw == null ? "" : raw.strip().toLowerCase();
        if (v.isEmpty()) {
            return v;
        }
        return v.contains(":") ? v : "minecraft:" + v;
    }
}
