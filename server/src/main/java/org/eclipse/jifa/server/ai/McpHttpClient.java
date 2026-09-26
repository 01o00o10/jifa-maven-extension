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
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.eclipse.jifa.common.util.GsonHolder.GSON;

@Service
public class McpHttpClient {

    private final AiProperties properties;
    private final HttpClient client;
    private final AtomicLong requestId = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean initialized = new java.util.concurrent.atomic.AtomicBoolean();

    public McpHttpClient(AiProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                                .build();
    }

    public boolean enabled() {
        return properties.getMcpUrl() != null && !properties.getMcpUrl().isBlank();
    }

    public List<JsonObject> listTools(String namespace) throws Exception {
        JsonObject result = request("tools/list", new JsonObject());
        List<JsonObject> tools = new ArrayList<>();
        if (!result.has("tools")) return tools;
        for (var element : result.getAsJsonArray("tools")) {
            JsonObject tool = element.getAsJsonObject();
            if (!tool.get("name").getAsString().startsWith(namespace + ".")) continue;
            JsonObject function = new JsonObject();
            function.addProperty("name", tool.get("name").getAsString());
            function.addProperty("description", tool.has("description") ? tool.get("description").getAsString() : "Jifa MCP analysis tool");
            function.add("parameters", tool.has("inputSchema") ? tool.get("inputSchema") : emptySchema());
            JsonObject openAiTool = new JsonObject();
            openAiTool.addProperty("type", "function");
            openAiTool.add("function", function);
            tools.add(openAiTool);
        }
        return tools;
    }

    public String call(String name, JsonObject arguments, String targetPath) throws Exception {
        JsonObject input = arguments == null ? new JsonObject() : arguments.deepCopy();
        input.addProperty("target", targetPath);
        JsonObject response = request("tools/call", input, name);
        return response.has("content") ? GSON.toJson(response.get("content")) : GSON.toJson(response);
    }

    private JsonObject request(String method, JsonObject params) throws Exception {
        return request(method, params, null);
    }

    private JsonObject request(String method, JsonObject params, String toolName) throws Exception {
        if (!"initialize".equals(method)) initialize();
        JsonObject payload = new JsonObject();
        payload.addProperty("jsonrpc", "2.0");
        payload.addProperty("id", requestId.incrementAndGet());
        payload.addProperty("method", method);
        JsonObject actualParams = params.deepCopy();
        if (toolName != null) {
            actualParams.addProperty("name", toolName);
            JsonObject arguments = actualParams.deepCopy();
            arguments.remove("name");
            actualParams.add("arguments", arguments);
            actualParams.remove("target");
        }
        payload.add("params", actualParams);
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getMcpUrl()))
                                         .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                                         .header("Content-Type", "application/json")
                                         .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                                         .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("MCP returned HTTP " + response.statusCode());
        JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
        if (json.has("error")) throw new IllegalStateException(json.getAsJsonObject("error").toString());
        return json.has("result") ? json.getAsJsonObject("result") : json;
    }

    private void initialize() throws Exception {
        if (initialized.get()) return;
        synchronized (initialized) {
            if (initialized.get()) return;
            JsonObject params = new JsonObject();
            params.addProperty("protocolVersion", "2025-03-26");
            JsonObject clientInfo = new JsonObject();
            clientInfo.addProperty("name", "jifa-server");
            clientInfo.addProperty("version", "0.3.0");
            params.add("clientInfo", clientInfo);
            JsonObject capabilities = new JsonObject();
            params.add("capabilities", capabilities);
            requestWithoutInitialization("initialize", params);
            initialized.set(true);
        }
    }

    private JsonObject requestWithoutInitialization(String method, JsonObject params) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("jsonrpc", "2.0");
        payload.addProperty("id", requestId.incrementAndGet());
        payload.addProperty("method", method);
        payload.add("params", params);
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getMcpUrl()))
                                         .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                                         .header("Content-Type", "application/json")
                                         .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                                         .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("MCP initialize returned HTTP " + response.statusCode());
        JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
        if (json.has("error")) throw new IllegalStateException(json.getAsJsonObject("error").toString());
        return json;
    }

    private JsonObject emptySchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }
}
