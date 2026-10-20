package dev.blockmate.protocol;

/**
 * 握手请求（细则第 4 节）。三个通道共用一个 record，用不到的字段留 null（JSON 里省略）。
 * mc：token, role="mc", player, owner, modVersion, protocolV；
 * ui：token, role="ui", client；
 * keeper：token, role="keeper", version。
 */
public record Hello(String token, String role, String player, String owner, String modVersion,
                    Integer protocolV, String client, String version) {

    public static Hello mc(String token, String player, String owner, String modVersion) {
        return new Hello(token, "mc", player, owner, modVersion, ProtocolCodec.VERSION, null, null);
    }

    public static Hello ui(String token, String client) {
        return new Hello(token, "ui", null, null, null, null, client, null);
    }

    public static Hello keeper(String token, String version) {
        return new Hello(token, "keeper", null, null, null, null, null, version);
    }
}
