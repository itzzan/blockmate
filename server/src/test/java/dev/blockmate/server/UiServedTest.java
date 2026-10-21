package dev.blockmate.server;

import dev.blockmate.server.gateway.ChatRequest;
import dev.blockmate.server.gateway.ModelClient;
import dev.blockmate.server.gateway.ModelReply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 手册 5.6：网页由后台直接提供，构建产物是静态文件。 */
class UiServedTest {

    @TempDir
    Path dir;
    ServerApp app;

    @AfterEach
    void stop() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void 后台能提供网页三件套() throws Exception {
        ModelClient noModel = new ModelClient() {
            @Override
            public ModelReply chat(ChatRequest request, Consumer<String> onChunk) {
                return new ModelReply("", List.of(), 0, 0, 0);
            }

            @Override
            public String name() {
                return "none";
            }
        };
        app = ServerApp.start(Config.of(dir, Map.of("token", "t")), 0, noModel);
        HttpClient http = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + app.port();

        HttpResponse<String> index = http.send(HttpRequest.newBuilder(URI.create(base + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("id=\"chat\""), "首页要有聊天区");
        assertTrue(index.body().contains("id=\"stop\""), "首页要有停按钮");
        assertTrue(index.body().contains("app.js"));

        HttpResponse<String> js = http.send(HttpRequest.newBuilder(URI.create(base + "/app.js")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, js.statusCode());
        assertTrue(js.body().contains("/ws/ui?token="), "脚本要连 /ws/ui");
        assertTrue(js.body().contains("companion_token"), "token 存浏览器本地");
        assertTrue(js.body().contains("'stop'"), "停按钮直接发 stop");

        HttpResponse<String> css = http.send(HttpRequest.newBuilder(URI.create(base + "/style.css")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, css.statusCode());
        assertTrue(css.body().contains("@media"), "要有手机宽度的样式");
    }
}
