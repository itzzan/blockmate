package dev.blockmate.protocol;

import com.google.gson.JsonArray;

/**
 * hub 对 hello 的回复（细则第 4 节）。
 * ok=false 时 error 说明原因，随后 hub 以 4401 关闭连接。
 * 对 ui 额外带 status 和 history（最近 N 条消息，原始 JSON 数组）；对 keeper 额外带 desired。
 */
public record HelloOk(boolean ok, Long serverTime, Integer protocolV, String desired, Status status,
                      JsonArray history, String error) {

    public static HelloOk ok(long serverTime) {
        return new HelloOk(true, serverTime, ProtocolCodec.VERSION, null, null, null, null);
    }

    public static HelloOk rejected(String error) {
        return new HelloOk(false, null, ProtocolCodec.VERSION, null, null, null, error);
    }
}
