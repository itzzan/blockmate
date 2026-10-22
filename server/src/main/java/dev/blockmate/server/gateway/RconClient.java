package dev.blockmate.server.gateway;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 极简 RCON 客户端（Source RCON 协议，和 ops/macmini/rcon.py 一样），给自动上下线查"谁在线"用。
 * 每次 command() 都是连接、登录、发一条命令、断开，不保持长连接；连接 3 秒、读 5 秒超时。
 */
public final class RconClient {

    private static final int TYPE_AUTH = 3;
    private static final int TYPE_COMMAND = 2;

    private final String host;
    private final int port;
    private final Supplier<String> password;

    public RconClient(String host, int port, Supplier<String> password) {
        this.host = host;
        this.port = port;
        this.password = password;
    }

    public String command(String cmd) throws IOException {
        String pw = password.get();
        if (pw == null || pw.isBlank()) {
            throw new IOException("RCON 密码为空");
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 3_000);
            s.setSoTimeout(5_000);
            OutputStream out = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());
            send(out, 1, TYPE_AUTH, pw);
            Packet auth = read(in);
            if (auth.id == -1) {
                throw new IOException("RCON 密码错误");
            }
            send(out, 2, TYPE_COMMAND, cmd);
            return read(in).body;
        }
    }

    /** 解析 `list` 的回复："There are 1 of a max of 20 players online: Blockmate" → [Blockmate]。 */
    public static List<String> parseList(String reply) throws IOException {
        if (reply == null || !reply.contains("players online")) {
            throw new IOException("list 回复看不懂: " + (reply == null ? "null" : reply.strip()));
        }
        int colon = reply.indexOf(':');
        List<String> names = new ArrayList<>();
        if (colon >= 0) {
            for (String n : reply.substring(colon + 1).split(",")) {
                String t = n.strip();
                if (!t.isEmpty()) {
                    names.add(t);
                }
            }
        }
        return names;
    }

    private static void send(OutputStream out, int id, int type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + 4 + b.length + 2).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(4 + 4 + b.length + 2).putInt(id).putInt(type).put(b).put((byte) 0).put((byte) 0);
        out.write(buf.array());
        out.flush();
    }

    private static Packet read(InputStream in) throws IOException {
        byte[] head = in.readNBytes(4);
        if (head.length < 4) {
            throw new IOException("RCON 连接被服务器关闭");
        }
        int len = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (len < 10 || len > 1 << 20) {
            throw new IOException("RCON 包长度不合法: " + len);
        }
        byte[] data = in.readNBytes(len);
        if (data.length < len) {
            throw new IOException("RCON 连接被服务器关闭");
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int id = buf.getInt();
        buf.getInt(); // type
        String body = new String(data, 8, len - 10, StandardCharsets.UTF_8);
        return new Packet(id, body);
    }

    private record Packet(int id, String body) {
    }
}
