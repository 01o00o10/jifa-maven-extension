package org.eclipse.jifa.server.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import org.eclipse.jifa.analysis.Api;
import org.eclipse.jifa.analysis.ApiParameter;
import org.eclipse.jifa.analysis.ApiService;
import org.eclipse.jifa.server.domain.dto.AnalysisApiRequest;
import org.eclipse.jifa.server.Configuration;
import org.eclipse.jifa.server.enums.FileType;
import org.eclipse.jifa.server.service.AnalysisApiService;
import org.eclipse.jifa.server.service.StorageService;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.eclipse.jifa.common.util.GsonHolder.GSON;

@Service
public class AiDiagnosisService {

    private final AiProvider provider;
    private final AiProperties properties;
    private final AnalysisApiService analysisApiService;
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
    private final Path sessionDirectory;
    private final McpHttpClient mcpClient;
    private final StorageService storageService;

    public AiDiagnosisService(AiProvider provider, AiProperties properties, AnalysisApiService analysisApiService,
                              Configuration configuration, McpHttpClient mcpClient,
                              @Nullable StorageService storageService) {
        this.provider = provider;
        this.properties = properties;
        this.analysisApiService = analysisApiService;
        this.mcpClient = mcpClient;
        this.storageService = storageService;
        Path storagePath = configuration.getStoragePath();
        if (storagePath == null) storagePath = Path.of(System.getProperty("user.home"), "jifa-storage");
        Path configuredSessionPath = properties.getSessionPath() == null || properties.getSessionPath().isBlank()
                                    ? storagePath.resolve("ai-sessions") : Path.of(properties.getSessionPath());
        this.sessionDirectory = configuredSessionPath.toAbsolutePath().normalize();
        try {
            Files.createDirectories(sessionDirectory);
        } catch (Exception e) {
            throw new IllegalStateException("无法创建 AI 会话目录: " + sessionDirectory, e);
        }
    }

    public CompletableFuture<DiagnosisResult> diagnose(String sessionId, String target, String fileType, String question) {
        return diagnose(sessionId, target, fileType, question, ignored -> {
        });
    }

    public CompletableFuture<DiagnosisResult> diagnose(String sessionId, String target, String fileType, String question,
                                                       Consumer<String> progress) {
        return diagnose(sessionId, target, fileType, question, progress, ignored -> {
        });
    }

    public CompletableFuture<DiagnosisResult> diagnose(String sessionId, String target, String fileType, String question,
                                                       Consumer<String> progress, Consumer<String> tokenSink) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String actualSessionId = validSessionId(sessionId) ? sessionId : UUID.randomUUID().toString();
                Conversation conversation = conversations.computeIfAbsent(actualSessionId,
                        ignored -> new Conversation(sessionDirectory.resolve(actualSessionId + ".json"), target, fileType));
                if (!conversation.matches(target, fileType)) {
                    conversation = new Conversation(sessionDirectory.resolve(actualSessionId + ".json"), target, fileType);
                    conversations.put(actualSessionId, conversation);
                }

                FileType type = FileType.valueOf(fileType);
                String namespace = type.getApiNamespace();
                Api api = selectSnapshotApi(namespace);
                String evidence = "暂无可执行的无额外参数分析 API";
                if (api != null) {
                    progress.accept("正在读取 Jifa 分析证据: " + api.name());
                    JsonObject request = new JsonObject();
                    request.addProperty("namespace", namespace);
                    request.addProperty("api", api.name());
                    request.addProperty("target", target);
                    request.add("parameters", new JsonObject());
                    Object value = analysisApiService.invoke(new AnalysisApiRequest(request)).join();
                    evidence = GSON.toJson(value);
                    if (evidence.length() > 120_000) evidence = evidence.substring(0, 120_000) + "\n[结果已截断]";
                }

                String system = "你是 Jifa Java 生产问题诊断助手。只能基于提供的真实分析证据回答，必须使用中文。" +
                                "请区分事实、推断、建议和置信度；证据不足时必须明确说明，不得编造。" +
                                "最终回答不得包含工具调用协议、DSML/XML 标签、data: 前缀或内部思考过程。" +
                                reportInstruction(fileType);
                String user = conversation.historyPrompt() +
                              "当前问题:\n文件类型: " + fileType + "\n" +
                              "文件标识: " + target + "\n" +
                              "用户问题: " + question + "\n" +
                              "Jifa 分析证据:\n" + evidence;
                String answer = provider.chatWithTools(system, user, toolDefinitions(namespace), toolCall -> {
                    try {
                        progress.accept("正在调用分析工具: " + toolCall.name() + "，参数: " + toolCall.arguments());
                        String result = executeTool(namespace, target, toolCall.name(), toolCall.arguments());
                        progress.accept("分析工具完成: " + toolCall.name() + "，结果长度: " + result.length());
                        return result;
                    } catch (Exception e) {
                        progress.accept("分析工具失败: " + toolCall.name());
                        return "工具执行失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    }
                }, tokenSink);
                conversation.add(question, answer);
                return new DiagnosisResult(actualSessionId, answer);
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        });
    }

    private String reportInstruction(String fileType) {
        return switch (fileType) {
            case "GC_LOG" -> "输出结构：结论、GC指标、疑似根因、风险等级、建议动作、还需补充的数据。";
            case "THREAD_DUMP" -> "输出结构：结论、线程状态证据、死锁或阻塞链、风险等级、建议动作、还需补充的数据。";
            case "HEAP_DUMP" -> "输出结构：结论、内存占用证据、疑似泄漏链、风险等级、建议动作、还需补充的数据。";
            case "JFR_FILE" -> "输出结构：结论、CPU/内存/锁证据、疑似热点、风险等级、建议动作、还需补充的数据。";
            default -> "输出结构：结论、证据、推断、风险等级、建议动作。";
        };
    }

    private List<JsonObject> toolDefinitions(String namespace) {
        if (mcpClient.enabled()) {
            try {
                return mcpClient.listTools(namespace);
            } catch (Exception e) {
                // An unavailable optional MCP endpoint must not disable built-in Jifa tools.
            }
        }
        Set<Api> apis = ApiService.getInstance().supportedApis().getOrDefault(namespace, Set.of());
        List<JsonObject> tools = new java.util.ArrayList<>();
        for (Api api : apis) {
            JsonObject function = new JsonObject();
            function.addProperty("name", namespace + "." + api.name());
            function.addProperty("description", "Execute Jifa analysis API " + namespace + "." + api.name());
            function.add("parameters", schema(api));
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            tool.add("function", function);
            tools.add(tool);
        }
        return tools;
    }

    private String executeTool(String namespace, String target, String toolName, JsonObject arguments) {
        String prefix = namespace + ".";
        if (!toolName.startsWith(prefix)) return "不允许调用其他文件类型的工具";
        if (mcpClient.enabled()) {
            try {
                if (storageService == null) return "MCP 模式缺少本地存储服务";
                FileType type = FileType.getByApiNamespace(namespace);
                Path targetPath = storageService.locationOf(type, target);
                return mcpClient.call(toolName, arguments, targetPath.toString());
            } catch (Exception e) {
                // MCP is optional; continue with the in-process executor when it is unavailable.
            }
        }
        String apiName = toolName.substring(prefix.length());
        Api api = ApiService.getInstance().supportedApis().getOrDefault(namespace, Set.of()).stream()
                           .filter(candidate -> candidate.name().equals(apiName) || candidate.aliases().contains(apiName))
                           .findFirst().orElseThrow(() -> new IllegalArgumentException("未知工具: " + toolName));
        JsonObject request = new JsonObject();
        request.addProperty("namespace", namespace);
        request.addProperty("api", api.name());
        request.addProperty("target", target);
        JsonObject parameters = arguments == null ? new JsonObject() : arguments.deepCopy();
        for (ApiParameter parameter : api.parameters()) {
            if (parameter.targetPath()) parameters.remove(parameter.name());
        }
        request.add("parameters", parameters);
        Object value = analysisApiService.invoke(new AnalysisApiRequest(request)).join();
        String result = GSON.toJson(value);
        return result.length() > 80_000 ? result.substring(0, 80_000) + "\n[工具结果已截断]" : result;
    }

    private JsonObject schema(Api api) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (ApiParameter parameter : api.parameters()) {
            if (parameter.targetPath()) continue;
            JsonObject property = new JsonObject();
            property.addProperty("type", jsonType(parameter.type()));
            properties.add(parameter.name(), property);
            if (parameter.required()) required.add(parameter.name());
        }
        schema.add("properties", properties);
        if (required.size() > 0) schema.add("required", required);
        return schema;
    }

    private String jsonType(java.lang.reflect.Type type) {
        String name = type.getTypeName();
        if (name.equals(boolean.class.getTypeName()) || name.equals(Boolean.class.getTypeName())) return "boolean";
        if (name.equals(int.class.getTypeName()) || name.equals(Integer.class.getTypeName()) ||
            name.equals(long.class.getTypeName()) || name.equals(Long.class.getTypeName()) ||
            name.equals(double.class.getTypeName()) || name.equals(Double.class.getTypeName())) return "number";
        return "string";
    }

    public void clear(String sessionId) {
        if (!validSessionId(sessionId)) return;
        conversations.remove(sessionId);
        try {
            Files.deleteIfExists(sessionDirectory.resolve(sessionId + ".json"));
        } catch (Exception e) {
            throw new IllegalStateException("无法清理 AI 会话: " + sessionId, e);
        }
    }

    private boolean validSessionId(String sessionId) {
        return sessionId != null && sessionId.matches("[A-Za-z0-9_-]{1,100}");
    }

    public record DiagnosisResult(String sessionId, String answer) {
    }

    private static final class Conversation {
        private static final int MAX_TURNS = 8;
        private final Path file;
        private final String target;
        private final String fileType;
        private final List<Turn> turns = new java.util.ArrayList<>();

        private Conversation(Path file, String target, String fileType) {
            this.file = file;
            this.target = target;
            this.fileType = fileType;
            load();
        }

        private synchronized boolean matches(String target, String fileType) {
            return this.target.equals(target) && this.fileType.equals(fileType);
        }

        private synchronized String historyPrompt() {
            if (turns.isEmpty()) return "";
            StringBuilder prompt = new StringBuilder("之前的对话（仅用于理解追问，不替代当前证据）：\n");
            for (Turn turn : turns) {
                if (unusableHistoryAnswer(turn.answer())) continue;
                prompt.append("用户: ").append(turn.question()).append('\n');
                prompt.append("助手: ").append(turn.answer()).append('\n');
            }
            return prompt.length() == "之前的对话（仅用于理解追问，不替代当前证据）：\n".length()
                   ? "" : prompt.append('\n').toString();
        }

        private synchronized void add(String question, String answer) {
            if (unusableHistoryAnswer(answer)) return;
            if (turns.size() >= MAX_TURNS) turns.remove(0);
            turns.add(new Turn(question, answer));
            save();
        }

        private static boolean unusableHistoryAnswer(String answer) {
            return answer == null || answer.isBlank() ||
                   answer.contains("DSML") || answer.contains("｜｜DSML") ||
                   answer.contains("data: data:") ||
                   answer.contains("AI 工具调用次数超过上限");
        }

        private void load() {
            try {
                if (!Files.isRegularFile(file)) return;
                JsonObject saved = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
                if (saved == null || !target.equals(saved.get("target").getAsString()) ||
                    !fileType.equals(saved.get("fileType").getAsString())) return;
                if (!saved.has("turns") || !saved.get("turns").isJsonArray()) return;
                for (var element : saved.getAsJsonArray("turns")) {
                    JsonObject turn = element.getAsJsonObject();
                    turns.add(new Turn(turn.get("question").getAsString(), turn.get("answer").getAsString()));
                }
                while (turns.size() > MAX_TURNS) turns.remove(0);
            } catch (Exception ignored) {
                turns.clear();
            }
        }

        private synchronized void save() {
            try {
                JsonObject saved = new JsonObject();
                saved.addProperty("target", target);
                saved.addProperty("fileType", fileType);
                saved.addProperty("machine", machineName());
                saved.addProperty("updatedAt", java.time.Instant.now().toString());
                JsonArray history = new JsonArray();
                for (Turn turn : turns) {
                    JsonObject item = new JsonObject();
                    item.addProperty("question", turn.question());
                    item.addProperty("answer", turn.answer());
                    history.add(item);
                }
                saved.add("turns", history);
                Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(temporary, GSON.toJson(saved), StandardCharsets.UTF_8);
                Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                           java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                try {
                    Files.move(file.resolveSibling(file.getFileName() + ".tmp"), file,
                               java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception failure) {
                    throw new IllegalStateException("无法保存 AI 会话: " + file, failure);
                }
            } catch (Exception e) {
                throw new IllegalStateException("无法保存 AI 会话: " + file, e);
            }
        }

        private String machineName() {
            return System.getProperty("user.name", "unknown") + "@" +
                   System.getProperty("os.name", "unknown") + ":" +
                   System.getProperty("os.arch", "unknown");
        }
    }

    private record Turn(String question, String answer) {
    }

    private Api selectSnapshotApi(String namespace) {
        Set<Api> apis = ApiService.getInstance().supportedApis().get(namespace);
        if (apis == null) return null;
        List<String> preferred = "heap-dump".equals(namespace)
                                 ? List.of("leakReport", "overview", "metadata", "diagnose", "analyze")
                                 : List.of("overview", "metadata", "globalDiagnoseInfo", "diagnoseInfo", "diagnose", "analyze");
        for (String name : preferred) {
            Api candidate = apis.stream().filter(api -> api.name().equals(name)).findFirst().orElse(null);
            if (candidate != null && onlyTargetPaths(candidate)) return candidate;
        }
        return apis.stream().filter(this::onlyTargetPaths).findFirst().orElse(null);
    }

    private boolean onlyTargetPaths(Api api) {
        for (ApiParameter parameter : api.parameters()) {
            if (!parameter.targetPath() && !parameter.comparisonTargetPath()) return false;
        }
        return true;
    }
}
