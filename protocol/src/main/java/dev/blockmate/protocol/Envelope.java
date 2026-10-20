package dev.blockmate.protocol;

import com.google.gson.JsonObject;

/**
 * 信封（细则第 2 节）：v / type / id / ts / from / to / body。
 * body 保持为原始 JSON 对象，用 {@link ProtocolCodec#bodyAs} 转成具体的 record。
 */
public record Envelope(int v, MessageType type, String id, long ts, Peer from, Peer to, JsonObject body) {
    public Envelope {
        if (body == null) {
            body = new JsonObject();
        }
    }
}
