/********************************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.jifa.server.ai;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestOpenAiCompatibleProvider {

    @Test
    void shouldExposeProviderCompatibleToolNamesAndKeepOriginalMapping() {
        Map<String, String> originalNames = new HashMap<>();
        List<JsonObject> compatible = OpenAiCompatibleProvider.compatibleTools(
                List.of(tool("heap-dump.overview"), tool("heap-dump_overview")), originalNames);

        String first = compatible.get(0).getAsJsonObject("function").get("name").getAsString();
        String second = compatible.get(1).getAsJsonObject("function").get("name").getAsString();

        assertEquals("heap-dump_overview", first);
        assertEquals("heap-dump_overview_2", second);
        assertTrue(first.matches("^[a-zA-Z0-9_-]+$"));
        assertTrue(second.matches("^[a-zA-Z0-9_-]+$"));
        assertEquals("heap-dump.overview", originalNames.get(first));
        assertEquals("heap-dump_overview", originalNames.get(second));
    }

    @Test
    void shouldForceFinalDiagnosisWhenModelRepeatsTheSameToolCall() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        List<String> requestBodies = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(requestBody);
            int requestNumber = requests.incrementAndGet();
            byte[] response;
            if (requestBody.contains("\"stream\":true")) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                response = ("data: {\"choices\":[{\"delta\":{\"content\":\"最终诊断：存在可疑强引用链。\"}}]}\n\n" +
                            "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            } else {
                String id = "call_" + requestNumber;
                response = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null," +
                            "\"tool_calls\":[{\"id\":\"" + id + "\",\"type\":\"function\"," +
                            "\"function\":{\"name\":\"heap-dump_histogram\"," +
                            "\"arguments\":\"{\\\"page\\\":1}\"}}]}}]}")
                        .getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            AiProperties properties = new AiProperties();
            properties.setEnabled(true);
            properties.setApiKey("test-key");
            properties.setModel("test-model");
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(properties);
            AtomicInteger executions = new AtomicInteger();

            String answer = provider.chatWithTools("system", "分析疑似内存泄漏",
                    List.of(tool("heap-dump.histogram")), call -> {
                        executions.incrementAndGet();
                        return "{\"retainedSize\":14706576}";
                    });

            assertEquals("最终诊断：存在可疑强引用链。", answer);
            assertEquals(1, executions.get(), "重复的工具调用不应再次执行");
            assertEquals(3, requests.get(), "两轮工具决策后应发起独立的最终总结请求");
            assertTrue(requestBodies.get(2).contains("工具探索阶段已经结束"));
            assertTrue(requestBodies.get(2).contains("retainedSize"));
            assertFalse(requestBodies.get(2).contains("\"tools\""));
        } finally {
            server.stop(0);
        }
    }

    private JsonObject tool(String name) {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        return tool;
    }
}
