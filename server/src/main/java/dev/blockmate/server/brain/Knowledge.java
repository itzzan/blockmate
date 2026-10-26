package dev.blockmate.server.brain;

import dev.blockmate.protocol.BlockRules;
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
 * 现在只有 plan_examples.md（常见任务怎么拆）；第二阶段后面还会有 blocks.yaml 等。
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
     * blocks.yaml 的 mineable / placeable 两段（任务单 12）。文件缺失或坏了返回空清单 = 什么都不许挖、不许放。
     * id 没写命名空间自动补 minecraft:，统一小写。
     */
    public synchronized BlockRules blockRules() {
        if (dir == null) {
            return rules;
        }
        Path p = dir.resolve("blocks.yaml");
        try {
            if (Files.exists(p)) {
                FileTime mtime = Files.getLastModifiedTime(p);
                if (rulesMtime == null || !mtime.equals(rulesMtime)) {
                    rules = parseRules(Files.readString(p, StandardCharsets.UTF_8));
                    rulesMtime = mtime;
                    log.info("knowledge loaded file=blocks.yaml mineable={} placeable={}", rules.mineable().size(),
                            rules.placeable().size());
                }
                return rules;
            }
        } catch (IOException | RuntimeException e) {
            log.warn("knowledge blocks.yaml read failed: {}（按空清单处理，什么都不许挖）", e.getMessage());
            return BlockRules.empty();
        }
        if (!rulesWarned) {
            log.warn("knowledge file missing: {}（没有白名单，伙伴什么都不许挖、不许放）", p);
            rulesWarned = true;
        }
        return BlockRules.empty();
    }

    static BlockRules parseRules(String yamlText) {
        Object loaded = new Yaml().load(yamlText);
        if (!(loaded instanceof Map<?, ?> m)) {
            return BlockRules.empty();
        }
        return new BlockRules(idList(m.get("mineable")), idList(m.get("placeable")));
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
