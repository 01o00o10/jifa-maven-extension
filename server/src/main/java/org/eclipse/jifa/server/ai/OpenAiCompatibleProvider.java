package org.eclipse.jifa.server.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Consumer;

import static org.eclipse.jifa.common.util.GsonHolder.GSON;

@Service
public class OpenAiCompatibleProvider implements AiProvider {

    private static final int MAX_TOOL_ROUNDS = 8;

    private static final int MAX_TOOL_RESULT_CHARS = 30_000;

    private static final int MAX_SYNTHESIS_EVIDENCE_CHARS = 100_000;

    private static final int MAX_SYNTHESIS_PROMPT_CHARS = 120_000;

    private final AiProperties properties;
    private final HttpClient client;

    public OpenAiCompatibleProvider(AiProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                                .build();
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) throws Exception {
        return chatWithTools(systemPrompt, userPrompt, List.of(), ignored -> "");
    }

    @Override
    public String chatWithTools(String systemPrompt, String userPrompt, List<JsonObject> tools,
                                Function<AiProvider.ToolCall, String> executor) throws Exception {
        return chatWithTools(systemPrompt, userPrompt, tools, executor, ignored -> {
        });
    }

    @Override
    public String chatWithTools(String systemPrompt, String userPrompt, List<JsonObject> tools,
                                Function<AiProvider.ToolCall, String> executor,
                                Consumer<String> tokenSink) throws Exception {
        if (!properties.isEnabled()) {
            return "AI 功能未启用，请配置 jifa.ai.enabled=true（兼容 jifa.ai.enable=true）。";
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            return "AI 尚未配置 API Key，请配置 JIFA_AI_API_KEY 或 jifa.ai.api-key。";
        }

        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        Map<String, String> originalToolNames = new HashMap<>();
        List<JsonObject> compatibleTools = compatibleTools(tools, originalToolNames);
        Set<String> executedCalls = new HashSet<>();
        StringBuilder collectedEvidence = new StringBuilder();

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            JsonObject request = new JsonObject();
            request.addProperty("model", properties.getModel());
            request.add("messages", messages);
            request.addProperty("temperature", properties.getTemperature());
            request.addProperty("max_tokens", properties.getMaxTokens());
            // Tool-call streams require assembling fragmented tool names and arguments.
            // Keep tool rounds non-streaming so providers return standard tool_calls.
            // Final synthesis uses a fresh transcript below; merely removing tools from
            // this transcript can make some DeepSeek models leak private DSML syntax.
            boolean streaming = compatibleTools.isEmpty();
            if (!compatibleTools.isEmpty() && !streaming) {
                JsonArray toolDefinitions = new JsonArray();
                compatibleTools.forEach(tool -> toolDefinitions.add(tool));
                request.add("tools", toolDefinitions);
                request.addProperty("tool_choice", "auto");
            }
            if (streaming) request.addProperty("stream", true);

            JsonObject payload = streaming ? null : send(request);
            if (streaming) return sendStreaming(request, tokenSink);
            JsonObject message = payload.getAsJsonArray("choices").get(0).getAsJsonObject()
                                          .getAsJsonObject("message");
            JsonArray toolCalls = message.has("tool_calls") && message.get("tool_calls").isJsonArray()
                                  ? message.getAsJsonArray("tool_calls") : new JsonArray();
            if (toolCalls.isEmpty()) {
                return message.has("content") && !message.get("content").isJsonNull()
                       ? emit(message.get("content").getAsString(), tokenSink) : "AI 没有返回文本内容。";
            }

            messages.add(message);
            boolean shouldSynthesize = round == MAX_TOOL_ROUNDS - 1;
            for (var element : toolCalls) {
                JsonObject call = element.getAsJsonObject();
                JsonObject function = call.getAsJsonObject("function");
                String id = call.get("id").getAsString();
                String modelToolName = function.get("name").getAsString();
                String name = originalToolNames.getOrDefault(modelToolName, modelToolName);
                JsonObject arguments = function.has("arguments") && function.get("arguments").isJsonObject()
                                       ? function.getAsJsonObject("arguments")
                                       : GSON.fromJson(function.get("arguments").getAsString(), JsonObject.class);
                String signature = name + "\n" + GSON.toJson(arguments);
                if (!executedCalls.add(signature)) {
                    shouldSynthesize = true;
                    continue;
                }
                String content = limit(executor.apply(new AiProvider.ToolCall(id, name, arguments)),
                                       MAX_TOOL_RESULT_CHARS, "\n[工具结果已截断]");
                JsonObject toolResult = new JsonObject();
                toolResult.addProperty("role", "tool");
                toolResult.addProperty("tool_call_id", id);
                toolResult.addProperty("content", content);
                messages.add(toolResult);
                appendEvidence(collectedEvidence, name, arguments, content);
            }
            if (shouldSynthesize) {
                return synthesize(systemPrompt, userPrompt, collectedEvidence.toString(), tokenSink);
            }
        }
        return synthesize(systemPrompt, userPrompt, collectedEvidence.toString(), tokenSink);
    }

    /**
     * Finish from a clean message list. Reusing the tool-call transcript here can make some
     * OpenAI-compatible models continue calling tools (or expose their private DSML syntax)
     * even after tools have been removed from the request.
     */
    private String synthesize(String systemPrompt, String userPrompt, String evidence,
                              Consumer<String> tokenSink) throws Exception {
        JsonArray synthesisMessages = new JsonArray();
        synthesisMessages.add(message("system", systemPrompt +
                "\n工具探索阶段已经结束。现在禁止调用任何工具，只能根据已有证据直接生成最终中文诊断。" +
                "不要输出工具调用、协议标记、DSML/XML、data: 前缀或内部思考过程。"));
        String synthesisPrompt = limit(userPrompt, MAX_SYNTHESIS_PROMPT_CHARS, "\n[原始上下文已截断]") +
                                 "\n\n工具阶段补充证据：\n" +
                                 (evidence.isBlank() ? "没有额外工具证据，请基于上面的 Jifa 初始分析证据作答。"
                                                     : evidence) +
                                 "\n\n请停止探索，立即输出最终诊断。";
        synthesisMessages.add(message("user", synthesisPrompt));

        JsonObject request = new JsonObject();
        request.addProperty("model", properties.getModel());
        request.add("messages", synthesisMessages);
        request.addProperty("temperature", properties.getTemperature());
        request.addProperty("max_tokens", properties.getMaxTokens());
        request.addProperty("stream", true);
        return sendStreaming(request, tokenSink);
    }

    private static void appendEvidence(StringBuilder evidence, String toolName, JsonObject arguments, String result) {
        if (evidence.length() >= MAX_SYNTHESIS_EVIDENCE_CHARS) return;
        String item = "工具: " + toolName + "\n参数: " + GSON.toJson(arguments) + "\n结果:\n" + result + "\n\n";
        int remaining = MAX_SYNTHESIS_EVIDENCE_CHARS - evidence.length();
        evidence.append(item, 0, Math.min(item.length(), remaining));
        if (item.length() > remaining) evidence.append("\n[补充证据已截断]\n");
    }

    private static String limit(String value, int maxChars, String suffix) {
        if (value == null) return "";
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + suffix;
    }

    /**
     * OpenAI-compatible providers only accept [A-Za-z0-9_-] in function names.
     * Jifa and MCP use names such as heap-dump.overview, so expose a safe name
     * to the model and map it back before executing the real tool.
     */
    static List<JsonObject> compatibleTools(List<JsonObject> tools, Map<String, String> originalNames) {
        List<JsonObject> result = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        for (JsonObject tool : tools) {
            JsonObject copy = tool.deepCopy();
            if (!copy.has("function") || !copy.get("function").isJsonObject()) continue;
            JsonObject function = copy.getAsJsonObject("function");
            if (!function.has("name")) continue;
            String originalName = function.get("name").getAsString();
            String compatibleName = compatibleToolName(originalName, usedNames);
            function.addProperty("name", compatibleName);
            originalNames.put(compatibleName, originalName);
            result.add(copy);
        }
        return result;
    }

    private static String compatibleToolName(String originalName, Set<String> usedNames) {
        String base = originalName == null ? "tool" : originalName.replaceAll("[^A-Za-z0-9_-]", "_");
        if (base.isBlank()) base = "tool";
        if (base.length() > 64) base = base.substring(0, 64);
        String candidate = base;
        int suffix = 2;
        while (!usedNames.add(candidate)) {
            String marker = "_" + suffix++;
            candidate = base.substring(0, Math.min(base.length(), 64 - marker.length())) + marker;
        }
        return candidate;
    }

    private JsonObject send(JsonObject request) throws Exception {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(properties.chatEndpoint()))
                                             .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                                             .header("Authorization", "Bearer " + properties.getApiKey())
                                             .header("Content-Type", "application/json")
                                             .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(request)))
                                             .build();
        HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("AI provider returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return GSON.fromJson(response.body(), JsonObject.class);
    }

    private String sendStreaming(JsonObject request, Consumer<String> tokenSink) throws Exception {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(properties.chatEndpoint()))
                                             .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                                             .header("Authorization", "Bearer " + properties.getApiKey())
                                             .header("Content-Type", "application/json")
                                             .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(request)))
                                             .build();
        HttpResponse<java.util.stream.Stream<String>> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("AI provider returned HTTP " + response.statusCode());
        }
        StringBuilder answer = new StringBuilder();
        try (java.util.stream.Stream<String> lines = response.body()) {
            lines.filter(line -> line.startsWith("data:")).forEach(line -> {
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) return;
                try {
                    JsonObject payload = GSON.fromJson(data, JsonObject.class);
                    JsonObject delta = payload.getAsJsonArray("choices").get(0).getAsJsonObject()
                                               .getAsJsonObject("delta");
                    if (delta != null && delta.has("content") && !delta.get("content").isJsonNull()) {
                        String token = delta.get("content").getAsString();
                        answer.append(token);
                        tokenSink.accept(token);
                    }
                } catch (RuntimeException ignored) {
                    // Ignore provider keep-alive or malformed non-content chunks.
                }
            });
        }
        return answer.toString();
    }

    private String emit(String content, Consumer<String> tokenSink) {
        tokenSink.accept(content);
        return content;
    }

    private JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }
}
