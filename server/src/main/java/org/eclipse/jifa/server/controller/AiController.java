package org.eclipse.jifa.server.controller;

import com.google.gson.JsonObject;
import org.eclipse.jifa.server.ai.AiDiagnosisService;
import org.eclipse.jifa.server.ai.AiProperties;
import org.eclipse.jifa.server.ai.AiProvider;
import org.eclipse.jifa.server.Configuration;
import org.eclipse.jifa.server.domain.dto.HttpRequestToWorker;
import org.eclipse.jifa.server.domain.entity.cluster.WorkerEntity;
import org.eclipse.jifa.server.domain.entity.shared.file.FileEntity;
import org.eclipse.jifa.server.enums.FileType;
import org.eclipse.jifa.server.enums.Role;
import org.eclipse.jifa.server.service.FileService;
import org.eclipse.jifa.server.service.WorkerService;
import org.springframework.lang.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;
import reactor.core.publisher.Flux;

@RestController
public class AiController {

    private final AiDiagnosisService diagnosisService;
    private final Configuration configuration;
    private final FileService fileService;
    private final WorkerService workerService;
    private final AiProperties aiProperties;
    private final AiProvider aiProvider;
    private volatile boolean runtimeConfigured;

    public AiController(AiDiagnosisService diagnosisService, @Nullable Configuration configuration,
                        @Nullable FileService fileService, @Nullable WorkerService workerService,
                        @Nullable AiProperties aiProperties, @Nullable AiProvider aiProvider) {
        this.diagnosisService = diagnosisService;
        this.configuration = configuration;
        this.fileService = fileService;
        this.workerService = workerService;
        this.aiProperties = aiProperties;
        this.aiProvider = aiProvider;
    }

    @PostMapping(path = "/ai/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody JsonObject request) {
        SseEmitter emitter = new SseEmitter(120_000L);
        String target = string(request, "target");
        String fileType = string(request, "fileType");
        String question = string(request, "message");
        String sessionId = string(request, "sessionId");
        if (target.isBlank() || fileType.isBlank() || question.isBlank()) {
            sendError(emitter, "target、fileType 和 message 都不能为空");
            return emitter;
        }

        if (shouldRouteToWorker()) {
            return proxyChat(emitter, request, target, fileType);
        }

        return localChat(emitter, request);
    }

    @PostMapping(path = "/ai/chat/internal/stream", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatInternalStream(@RequestBody JsonObject request) {
        applyRuntimeConfig(request);
        return localChat(new SseEmitter(120_000L), request);
    }

    private SseEmitter localChat(SseEmitter emitter, JsonObject request) {
        CompletableFuture<AiDiagnosisService.DiagnosisResult> future = diagnosisService.diagnose(
                string(request, "sessionId"), string(request, "target"), string(request, "fileType"),
                string(request, "message"), progress -> send(emitter, "status", progress),
                token -> send(emitter, "delta", token));
        future.whenComplete((result, error) -> {
            try {
                if (error != null) {
                    send(emitter, "error", error.getCause() == null ? error.getMessage() : error.getCause().getMessage());
                } else {
                    send(emitter, "session", result.sessionId());
                    send(emitter, "message", result.answer());
                    send(emitter, "done", "{}");
                }
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    @PostMapping(path = "/ai/chat/internal", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public CompletableFuture<AiDiagnosisService.DiagnosisResult> chatInternal(@RequestBody JsonObject request) {
        applyRuntimeConfig(request);
        return diagnosisService.diagnose(string(request, "sessionId"), string(request, "target"),
                                         string(request, "fileType"), string(request, "message"));
    }

    @PostMapping(path = "/ai/session/clear", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void clear(@RequestBody JsonObject request) {
        if (shouldRouteToWorker() && request.has("target") && request.has("fileType")) {
            WorkerEntity worker = workerFor(string(request, "target"), string(request, "fileType"));
            if (worker != null) {
                workerService.asyncRequest(worker, new HttpRequestToWorker<>(org.springframework.http.HttpMethod.POST,
                        "ai/session/clear/internal", null, request, Void.class));
                return;
            }
        }
        diagnosisService.clear(string(request, "sessionId"));
    }

    @org.springframework.web.bind.annotation.GetMapping(path = "/ai/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonObject config() {
        JsonObject result = new JsonObject();
        if (aiProperties == null) return result;
        result.addProperty("enabled", aiProperties.isEnabled());
        result.addProperty("provider", aiProperties.getProvider());
        result.addProperty("model", aiProperties.getModel());
        result.addProperty("baseUrl", aiProperties.getBaseUrl());
        result.addProperty("mcpUrl", aiProperties.getMcpUrl());
        result.addProperty("mcpEnabled", aiProperties.getMcpUrl() != null && !aiProperties.getMcpUrl().isBlank());
        result.addProperty("apiKeyConfigured", aiProperties.getApiKey() != null && !aiProperties.getApiKey().isBlank());
        result.addProperty("configurationSource", runtimeConfigured ? "runtime" : "application.yml");
        return result;
    }

    @PostMapping(path = "/ai/config", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonObject updateConfig(@RequestBody JsonObject request) {
        if (aiProperties == null) return config();
        if (request.has("enabled")) aiProperties.setEnabled(request.get("enabled").getAsBoolean());
        if (request.has("provider")) aiProperties.setProvider(request.get("provider").getAsString());
        if (request.has("model")) aiProperties.setModel(request.get("model").getAsString());
        if (request.has("baseUrl")) aiProperties.setBaseUrl(request.get("baseUrl").getAsString());
        if (request.has("mcpUrl")) aiProperties.setMcpUrl(request.get("mcpUrl").getAsString());
        if (request.has("apiKey") && !request.get("apiKey").getAsString().isBlank()) {
            aiProperties.setApiKey(request.get("apiKey").getAsString());
        }
        runtimeConfigured = true;
        return config();
    }

    @PostMapping(path = "/ai/config/test", produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonObject testConfig() {
        JsonObject result = new JsonObject();
        try {
            if (aiProvider == null) throw new IllegalStateException("AI Provider 未加载");
            String answer = aiProvider.chat("你是连接测试助手，只返回 OK。", "请返回 OK");
            boolean success = answer != null && !answer.contains("未启用") && !answer.contains("尚未配置");
            result.addProperty("success", success);
            result.addProperty("message", success ? "AI 连接成功" : answer);
        } catch (Exception e) {
            result.addProperty("success", false);
            result.addProperty("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return result;
    }

    @PostMapping(path = "/ai/session/clear/internal", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void clearInternal(@RequestBody JsonObject request) {
        diagnosisService.clear(string(request, "sessionId"));
    }

    private boolean shouldRouteToWorker() {
        return configuration != null && configuration.getRole() == Role.MASTER &&
               fileService != null && workerService != null;
    }

    private SseEmitter proxyChat(SseEmitter emitter, JsonObject request, String target, String fileType) {
        WorkerEntity worker;
        try {
            worker = workerFor(target, fileType);
        } catch (Exception e) {
            sendError(emitter, e.getMessage());
            return emitter;
        }
        if (worker == null) {
            sendError(emitter, "找不到分析文件所属的 worker");
            return emitter;
        }
        JsonObject workerRequest = request.deepCopy();
        workerRequest.add("aiConfig", runtimeConfig());
        StringBuilder buffer = new StringBuilder();
        workerService.streamRequest(worker, new HttpRequestToWorker<>(org.springframework.http.HttpMethod.POST,
                "ai/chat/internal/stream", null, workerRequest, String.class))
                     .subscribe(chunk -> relayEvents(emitter, buffer, chunk),
                                error -> { send(emitter, "error", error.getMessage()); emitter.complete(); },
                                () -> { relayEvents(emitter, buffer, "\n\n"); emitter.complete(); });
        return emitter;
    }

    private void relayEvents(SseEmitter emitter, StringBuilder buffer, String chunk) {
        buffer.append(chunk);
        String value = buffer.toString();
        int boundary;
        while ((boundary = value.indexOf("\n\n")) >= 0) {
            String event = value.substring(0, boundary);
            value = value.substring(boundary + 2);
            String name = event.lines().filter(line -> line.startsWith("event:")).findFirst()
                                 .map(line -> line.substring(6).trim()).orElse("message");
            String data = event.lines().filter(line -> line.startsWith("data:")).findFirst()
                                 .map(line -> line.substring(5).trim()).orElse("");
            if (!data.isBlank()) send(emitter, name, data);
        }
        buffer.setLength(0);
        buffer.append(value);
    }

    private WorkerEntity workerFor(String target, String fileType) {
        FileEntity file = fileService.getFileByUniqueName(target, FileType.valueOf(fileType));
        WorkerEntity worker = fileService.getStaticWorkerByFile(file).orElse(null);
        return worker == null ? workerService.requestElasticWorkerForAnalysisApiRequest(file) : worker;
    }

    private String string(JsonObject request, String name) {
        return request.has(name) && request.get(name).isJsonPrimitive() ? request.get(name).getAsString() : "";
    }

    /**
     * Page configuration is in-memory. In master/worker mode the analysis runs
     * on a worker, so copy the current master configuration to that worker for
     * this request. The key never goes to the browser, only over the existing
     * internal master-worker request.
     */
    private JsonObject runtimeConfig() {
        JsonObject result = new JsonObject();
        if (aiProperties == null) return result;
        result.addProperty("enabled", aiProperties.isEnabled());
        result.addProperty("provider", aiProperties.getProvider());
        result.addProperty("model", aiProperties.getModel());
        result.addProperty("baseUrl", aiProperties.getBaseUrl());
        result.addProperty("apiKey", aiProperties.getApiKey());
        result.addProperty("temperature", aiProperties.getTemperature());
        result.addProperty("maxTokens", aiProperties.getMaxTokens());
        result.addProperty("timeoutSeconds", aiProperties.getTimeoutSeconds());
        result.addProperty("sessionPath", aiProperties.getSessionPath());
        result.addProperty("mcpUrl", aiProperties.getMcpUrl());
        return result;
    }

    private void applyRuntimeConfig(JsonObject request) {
        if (aiProperties == null || !request.has("aiConfig") || !request.get("aiConfig").isJsonObject()) return;
        JsonObject config = request.getAsJsonObject("aiConfig");
        if (config.has("enabled")) aiProperties.setEnabled(config.get("enabled").getAsBoolean());
        if (config.has("provider")) aiProperties.setProvider(config.get("provider").getAsString());
        if (config.has("model")) aiProperties.setModel(config.get("model").getAsString());
        if (config.has("baseUrl")) aiProperties.setBaseUrl(config.get("baseUrl").getAsString());
        if (config.has("apiKey")) aiProperties.setApiKey(config.get("apiKey").getAsString());
        if (config.has("temperature")) aiProperties.setTemperature(config.get("temperature").getAsDouble());
        if (config.has("maxTokens")) aiProperties.setMaxTokens(config.get("maxTokens").getAsInt());
        if (config.has("timeoutSeconds")) aiProperties.setTimeoutSeconds(config.get("timeoutSeconds").getAsInt());
        if (config.has("sessionPath")) aiProperties.setSessionPath(config.get("sessionPath").getAsString());
        if (config.has("mcpUrl")) aiProperties.setMcpUrl(config.get("mcpUrl").getAsString());
    }

    private void sendError(SseEmitter emitter, String message) {
        send(emitter, "error", message);
        emitter.complete();
    }

    private void send(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }
}
