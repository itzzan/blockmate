package dev.blockmate.server.gateway;

import java.util.function.Consumer;

/** Gateway 的接口（手册 5.5 第 4 条）。 */
public interface ModelClient {

    /** 发一次请求；文本分片通过 onChunk 流出，整体结果作为返回值。 */
    ModelReply chat(ChatRequest request, Consumer<String> onChunk) throws ModelException;

    String name();
}
