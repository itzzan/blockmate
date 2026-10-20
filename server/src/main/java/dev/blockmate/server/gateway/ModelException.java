package dev.blockmate.server.gateway;

/** 模型调不通：超时 / 连不上 / 回复不合法。 */
public class ModelException extends Exception {

    public enum Kind { TIMEOUT, CONNECT, BAD_RESPONSE }

    private final Kind kind;

    public ModelException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public ModelException(Kind kind, String message) {
        this(kind, message, null);
    }

    public Kind kind() {
        return kind;
    }
}
