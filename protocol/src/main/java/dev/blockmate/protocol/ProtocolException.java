package dev.blockmate.protocol;

/** 信封不合法（版本不对、type 不认识、缺 id 等）。 */
public class ProtocolException extends RuntimeException {
    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
