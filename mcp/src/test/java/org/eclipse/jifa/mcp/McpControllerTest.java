package org.eclipse.jifa.mcp;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpControllerTest {
    @Test
    void listsDiagnosticTool() {
        JsonObject request = new JsonObject();
        request.addProperty("method", "tools/list");
        JsonObject response = new McpController().handleRequest(request);
        assertEquals("diagnose_file", response.getAsJsonObject("result")
                .getAsJsonArray("tools").get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void rejectsMissingTarget() {
        JsonObject request = new JsonObject();
        request.addProperty("method", "tools/call");
        JsonObject params = new JsonObject();
        params.add("arguments", new JsonObject());
        request.add("params", params);
        assertEquals(-32602, new McpController().handleRequest(request).getAsJsonObject("result")
                .getAsJsonObject("error").get("code").getAsInt());
    }
}
