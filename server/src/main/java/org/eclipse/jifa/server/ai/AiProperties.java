package org.eclipse.jifa.server.ai;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "jifa.ai")
public class AiProperties {

    private boolean enabled = false;
    private String provider = "deepseek";
    private String apiKey = "";
    private String baseUrl = "https://api.deepseek.com";
    private String model = "deepseek-chat";
    private double temperature = 0.2;
    private int maxTokens = 4096;
    private int timeoutSeconds = 120;
    private String sessionPath = "";
    private String mcpUrl = "";

    /**
     * Backward-compatible alias for deployments that use jifa.ai.enable.
     * The documented property remains jifa.ai.enabled.
     */
    public void setEnable(boolean enable) {
        this.enabled = enable;
    }

    public String chatEndpoint() {
        if (baseUrl.endsWith("/chat/completions")) return baseUrl;
        if (baseUrl.endsWith("/v1")) return baseUrl + "/chat/completions";
        return baseUrl.replaceAll("/$", "") + "/v1/chat/completions";
    }
}
