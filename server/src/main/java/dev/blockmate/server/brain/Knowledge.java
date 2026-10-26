package dev.blockmate.server.brain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

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
}
