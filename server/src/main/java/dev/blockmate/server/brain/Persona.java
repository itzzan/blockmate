package dev.blockmate.server.brain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/** persona.md：每次用之前看修改时间，变了就重读（细则第 12 节）。文件不存在时用内置的最简人设并警告。 */
public final class Persona {

    private static final Logger log = LoggerFactory.getLogger(Persona.class);

    private final Path path;
    private final String fallback;
    private String text;
    private FileTime loadedMtime;
    private boolean warnedMissing;

    public Persona(Path path, String companionName, String ownerName) {
        this.path = path;
        this.fallback = defaultText(companionName, ownerName);
    }

    public synchronized String text() {
        try {
            if (Files.exists(path)) {
                FileTime mtime = Files.getLastModifiedTime(path);
                if (text == null || !mtime.equals(loadedMtime)) {
                    text = Files.readString(path, StandardCharsets.UTF_8);
                    loadedMtime = mtime;
                    log.info("persona loaded len={}", text.length());
                }
                return text;
            }
        } catch (IOException e) {
            log.warn("persona read failed: {}", e.getMessage());
        }
        if (!warnedMissing) {
            log.warn("persona file missing: {}，用内置人设", path);
            warnedMissing = true;
        }
        return fallback;
    }

    static String defaultText(String companion, String owner) {
        return "你叫 " + companion + "，是 " + owner + " 在 Minecraft 里的游戏伙伴，也是平时聊天的朋友。\n"
                + "说话短，一次不超过两句，不自称 AI 或模型。\n"
                + "主人要你做动作时必须调用对应的工具，不要只用文字答应。一次只做一件事，做不到就直说。\n";
    }
}
