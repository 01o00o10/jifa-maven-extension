package org.eclipse.jifa.mcp;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpControllerTest {

    private McpController controller() {
        return new McpController(new McpToolService(System.getProperty("user.dir")));
    }

    @Test
    void listsJifaTools() {
        JsonObject request = new JsonObject();
        request.addProperty("method", "tools/list");
        JsonObject response = controller().handleRequest(request);
        boolean hasDetectTool = false;
        for (var tool : response.getAsJsonObject("result").getAsJsonArray("tools")) {
            if ("file.detect".equals(tool.getAsJsonObject().get("name").getAsString())) {
                hasDetectTool = true;
                break;
            }
        }
        assertEquals(true, hasDetectTool);
    }

    @Test
    void rejectsMissingTarget() {
        JsonObject request = new JsonObject();
        request.addProperty("method", "tools/call");
        JsonObject params = new JsonObject();
        params.add("arguments", new JsonObject());
        request.add("params", params);
        assertEquals(-32602, controller().handleRequest(request).getAsJsonObject("result")
                .getAsJsonObject("error").get("code").getAsInt());
    }
}
