package dev.blockmate.server.gateway;

import java.time.Duration;
import java.util.function.Consumer;

/** Gateway 的接口（手册 5.5 第 4 条）。 */
public interface ModelClient {

    /** 发一次请求；文本分片通过 onChunk 流出，整体结果作为返回值。 */
    ModelReply chat(ChatRequest request, Consumer<String> onChunk) throws ModelException;

    /** 同上，但这一次用指定的超时（写日记这种大提示要等得久一点）。默认实现忽略超时参数。 */
    default ModelReply chat(ChatRequest request, Consumer<String> onChunk, Duration firstToken, Duration total)
            throws ModelException {
        return chat(request, onChunk);
    }

    String name();
}
