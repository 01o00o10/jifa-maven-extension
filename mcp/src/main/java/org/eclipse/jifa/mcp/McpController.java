package org.eclipse.jifa.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.eclipse.jifa.analysis.ApiService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.eclipse.jifa.common.util.GsonHolder.GSON;

@RestController
public class McpController {
    @PostMapping(path = "/mcp", consumes = "application/json", produces = "application/json")
    public String handle(@RequestBody String body) {
        JsonObject request = GSON.fromJson(body, JsonObject.class);
        return GSON.toJson(handleRequest(request));
    }

    JsonObject handleRequest(JsonObject request) {
        String method = request.has("method") ? request.get("method").getAsString() : "";
        JsonObject result = switch (method) {
            case "initialize" -> initialize();
            case "tools/list" -> tools();
            case "tools/call" -> call(request);
            default -> error(-32601, "Unsupported MCP method: " + method);
        };
        JsonObject response = new JsonObject();
        if (request.has("id")) response.add("id", request.get("id"));
        response.add("result", result);
        return response;
    }

    private JsonObject initialize() {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", "2024-11-05");
        result.add("capabilities", new JsonObject());
        return result;
    }

    private JsonObject tools() {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", "diagnose_file");
        tool.addProperty("description", "Detect the Jifa analysis namespace and metadata of a local file");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonObject target = new JsonObject();
        target.addProperty("type", "string");
        properties.add("target", target);
        schema.add("properties", properties);
        tool.add("inputSchema", schema);
        JsonArray tools = new JsonArray();
        tools.add(tool);
        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    private JsonObject call(JsonObject request) {
        JsonObject arguments = request.getAsJsonObject("params").getAsJsonObject("arguments");
        if (!arguments.has("target")) return error(-32602, "target is required");
        try {
            Path target = Path.of(arguments.get("target").getAsString()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(target)) return error(-32602, "target is not a readable file");
            byte[] content = Files.readAllBytes(target);
            JsonObject diagnosis = new JsonObject();
            diagnosis.addProperty("target", target.toString());
            diagnosis.addProperty("size", content.length);
            diagnosis.addProperty("namespace", ApiService.getInstance().deduceNamespaceByContent(content));
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", GSON.toJson(diagnosis));
            JsonArray contents = new JsonArray();
            contents.add(text);
            JsonObject result = new JsonObject();
            result.add("content", contents);
            return result;
        } catch (Exception e) {
            return error(-32000, e.getMessage());
        }
    }

    private JsonObject error(int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        JsonObject result = new JsonObject();
        result.add("error", error);
        return result;
    }
}
