package org.eclipse.jifa.mcp;

import com.google.gson.JsonObject;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.eclipse.jifa.common.util.GsonHolder.GSON;

@RestController
public class McpController {

    private final McpToolService toolService;

    public McpController(McpToolService toolService) {
        this.toolService = toolService;
    }
    @PostMapping(path = "/mcp", consumes = "application/json", produces = "application/json")
    public String handle(@RequestBody String body) {
        JsonObject request = GSON.fromJson(body, JsonObject.class);
        return GSON.toJson(handleRequest(request));
    }

    JsonObject handleRequest(JsonObject request) {
        String method = request.has("method") ? request.get("method").getAsString() : "";
        JsonObject result = switch (method) {
            case "initialize" -> initialize();
            case "tools/list" -> toolService.listTools();
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

    private JsonObject call(JsonObject request) {
        JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
        String name = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject arguments = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();
        return toolService.call(name, arguments);
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
