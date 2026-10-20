package dev.blockmate.protocol;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.UUID;

/**
 * 信封的编码 / 解码 / 校验（细则第 2 节）。
 * JSON 键一律小写下划线；值为 null 的字段省略，省略与 null 等价。
 */
public final class ProtocolCodec {

    /** 协议版本（信封的 v）。 */
    public static final int VERSION = 1;

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .disableHtmlEscaping()
            .create();

    private ProtocolCodec() {
    }

    public static Gson gson() {
        return GSON;
    }

    /** 新建一条信封：自动生成 id（UUID v4）和 ts（当前毫秒）。 */
    public static Envelope envelope(MessageType type, Peer from, Peer to, Object body) {
        return new Envelope(VERSION, type, UUID.randomUUID().toString(), System.currentTimeMillis(), from, to,
                toBody(body));
    }

    public static String encode(Envelope envelope) {
        return GSON.toJson(envelope);
    }

    public static JsonObject encodeToTree(Envelope envelope) {
        return GSON.toJsonTree(envelope).getAsJsonObject();
    }

    /** 解析并校验。不合法抛 {@link ProtocolException}。 */
    public static Envelope decode(String json) {
        JsonObject object;
        try {
            object = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new ProtocolException("不是一个 JSON 对象", e);
        }
        return decode(object);
    }

    public static Envelope decode(JsonObject object) {
        Envelope envelope;
        try {
            envelope = GSON.fromJson(object, Envelope.class);
        } catch (RuntimeException e) {
            throw new ProtocolException("信封格式不对", e);
        }
        validate(envelope, object);
        return envelope;
    }

    private static void validate(Envelope envelope, JsonObject raw) {
        if (envelope.v() != VERSION) {
            throw new ProtocolException("不支持的协议版本 v=" + envelope.v());
        }
        if (envelope.type() == null) {
            throw new ProtocolException("不认识的 type: " + raw.get("type"));
        }
        if (envelope.id() == null || envelope.id().isBlank()) {
            throw new ProtocolException("缺 id");
        }
        if (envelope.from() == null || envelope.to() == null) {
            throw new ProtocolException("from / to 缺失或不认识: " + raw.get("from") + " -> " + raw.get("to"));
        }
    }

    /** 把 body 转成具体的 record。 */
    public static <T> T bodyAs(Envelope envelope, Class<T> cls) {
        return GSON.fromJson(envelope.body(), cls);
    }

    /** 把 record（或 JsonObject）转成 body。null 得到空对象。 */
    public static JsonObject toBody(Object body) {
        if (body == null) {
            return new JsonObject();
        }
        return GSON.toJsonTree(body).getAsJsonObject();
    }

    /**
     * 按 type（hello 还要看方向）给出 body 对应的 record 类。
     * stop（ui 的"停"按钮）body 为空，返回 JsonObject。
     */
    public static Class<?> bodyClassFor(Envelope envelope) {
        switch (envelope.type()) {
            case HELLO:
                return envelope.from() == Peer.HUB ? HelloOk.class : Hello.class;
            case SNAPSHOT:
                return Snapshot.class;
            case EVENT:
                return Event.class;
            case COMMAND:
                return Command.class;
            case ACK:
                return Ack.class;
            case PROGRESS:
                return Progress.class;
            case RESULT:
                return Result.class;
            case CHAT:
                return Chat.class;
            case REPLY:
                return Reply.class;
            case STATUS:
                return Status.class;
            case STOP:
                return JsonObject.class;
            case PREFERENCES:
                return Preferences.class;
            case PREFERENCE_ACTION:
                return PreferenceAction.class;
            case KEEPER_CONTROL:
                return KeeperControl.class;
            case NOTICE:
                return Notice.class;
            case KEEPER_CMD:
                return KeeperCmd.class;
            case KEEPER_STATUS:
                return KeeperStatus.class;
            default:
                throw new ProtocolException("没有对应 body 类型的 type: " + envelope.type());
        }
    }
}
