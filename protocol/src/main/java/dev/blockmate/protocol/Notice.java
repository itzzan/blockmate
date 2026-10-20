package dev.blockmate.protocol;

/** hub → ui：固定的中文提示。level 是 info 或 warn。 */
public record Notice(String text, String level) {
    public static Notice info(String text) {
        return new Notice(text, "info");
    }

    public static Notice warn(String text) {
        return new Notice(text, "warn");
    }
}
