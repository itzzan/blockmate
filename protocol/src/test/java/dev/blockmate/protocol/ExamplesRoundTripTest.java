package dev.blockmate.protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 手册 5.3 第 3 条：examples/ 下每个样例读入、解析成 record、再序列化，必须和原文一致。 */
class ExamplesRoundTripTest {

    private static final Path DIR = Path.of("examples");

    static List<Path> files() throws IOException {
        try (Stream<Path> s = Files.list(DIR)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    @Test
    void 至少有十二个样例() throws IOException {
        assertTrue(files().size() >= 12, "examples/ 里至少 12 个文件，现在 " + files().size());
    }

    @TestFactory
    Stream<DynamicTest> 每个样例往返一致() throws IOException {
        return files().stream().map(p -> DynamicTest.dynamicTest(p.getFileName().toString(), () -> {
            String text = Files.readString(p);
            JsonObject original = JsonParser.parseString(text).getAsJsonObject();

            Envelope envelope = ProtocolCodec.decode(text);
            Class<?> cls = ProtocolCodec.bodyClassFor(envelope);
            Object typed = ProtocolCodec.bodyAs(envelope, cls);
            assertNotNull(typed, "body 应能转成 " + cls.getSimpleName());

            JsonObject bodyAgain = ProtocolCodec.toBody(typed);
            assertEquals(envelope.body(), bodyAgain, "body 经 " + cls.getSimpleName() + " 往返后应一致");

            Envelope rebuilt = new Envelope(envelope.v(), envelope.type(), envelope.id(), envelope.ts(),
                    envelope.from(), envelope.to(), bodyAgain);
            JsonObject encoded = JsonParser.parseString(ProtocolCodec.encode(rebuilt)).getAsJsonObject();
            assertEquals(original, encoded, "整条消息往返后应一致");
        }));
    }
}
