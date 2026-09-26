package org.eclipse.jifa.server.ai;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.function.Function;
import java.util.function.Consumer;

public interface AiProvider {

    String chat(String systemPrompt, String userPrompt) throws Exception;

    default String chatWithTools(String systemPrompt, String userPrompt, List<JsonObject> tools,
                                 Function<ToolCall, String> executor) throws Exception {
        return chatWithTools(systemPrompt, userPrompt, tools, executor, ignored -> {
        });
    }

    default String chatWithTools(String systemPrompt, String userPrompt, List<JsonObject> tools,
                                 Function<ToolCall, String> executor, Consumer<String> tokenSink) throws Exception {
        return chat(systemPrompt, userPrompt);
    }

    record ToolCall(String id, String name, JsonObject arguments) {
    }
}
