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
package org.eclipse.jifa.server.controller;

import org.eclipse.jifa.server.Constant;
import org.eclipse.jifa.server.ai.AiDiagnosisService;
import org.eclipse.jifa.server.ai.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = AiController.class, excludeAutoConfiguration = {SecurityAutoConfiguration.class})
public class TestAiController {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AiDiagnosisService diagnosisService;

    @MockitoBean
    private AiProperties aiProperties;

    @Test
    public void shouldReturnSessionAndAnswerAsSse() throws Exception {
        when(diagnosisService.diagnose(eq(""), eq("thread.log"), eq("THREAD_DUMP"), eq("check deadlock"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(
                        new AiDiagnosisService.DiagnosisResult("session-1", "no deadlock")));

        MvcResult result = mvc.perform(post(Constant.HTTP_API_PREFIX + "/ai/chat")
                                               .contentType(MediaType.APPLICATION_JSON)
                                               .accept(MediaType.TEXT_EVENT_STREAM)
                                               .content("""
                                                       {
                                                         "target": "thread.log",
                                                         "fileType": "THREAD_DUMP",
                                                         "message": "check deadlock"
                                                       }"""))
                              .andExpect(request().asyncStarted())
                              .andReturn();

        mvc.perform(asyncDispatch(result))
           .andExpect(status().isOk())
           .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
           .andExpect(content().string(org.hamcrest.Matchers.containsString("data:\"session-1\"")))
           .andExpect(content().string(org.hamcrest.Matchers.containsString("data:\"no deadlock\"")));
    }

    @Test
    public void shouldClearSession() throws Exception {
        mvc.perform(post(Constant.HTTP_API_PREFIX + "/ai/session/clear")
                           .contentType(MediaType.APPLICATION_JSON)
                           .content("{\"sessionId\":\"session-1\"}"))
           .andExpect(status().isOk());
        verify(diagnosisService).clear("session-1");
    }

    @Test
    public void shouldApplyPageConfigurationToRuntimeProperties() throws Exception {
        mvc.perform(post(Constant.HTTP_API_PREFIX + "/ai/config")
                           .contentType(MediaType.APPLICATION_JSON)
                           .content("""
                                    {
                                      "enabled": true,
                                      "provider": "deepseek",
                                      "model": "deepseek-v4-flash",
                                      "baseUrl": "https://api.deepseek.com",
                                      "mcpUrl": "http://127.0.0.1:18081/mcp",
                                      "apiKey": "runtime-key"
                                    }"""))
           .andExpect(status().isOk())
           .andExpect(content().string(org.hamcrest.Matchers.containsString("\"configurationSource\":\"runtime\"")));

        verify(aiProperties).setEnabled(true);
        verify(aiProperties).setModel("deepseek-v4-flash");
        verify(aiProperties).setApiKey("runtime-key");
    }
}
