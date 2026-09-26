package org.eclipse.jifa.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.jifa.analysis.Api;
import org.eclipse.jifa.analysis.ApiParameter;
import org.eclipse.jifa.analysis.ApiService;
import org.eclipse.jifa.common.util.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.eclipse.jifa.common.util.GsonHolder.GSON;

@Service
public class McpToolService {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpToolService.class);

    private final ApiService apiService = ApiService.getInstance();
    private final Path allowedRoot;

    public McpToolService(@Value("${jifa.mcp.allowed-root:${user.home}/jifa-storage}") String allowedRoot) {
        this.allowedRoot = Path.of(allowedRoot).toAbsolutePath().normalize();
    }

    public JsonObject listTools() {
        JsonArray tools = new JsonArray();
        apiService.supportedApis().forEach((namespace, apis) -> apis.forEach(api -> {
            JsonObject tool = new JsonObject();
            tool.addProperty("name", namespace + "." + api.name());
            tool.addProperty("description", "Execute Jifa analysis API " + namespace + "." + api.name());
            tool.add("inputSchema", schema(api));
            tools.add(tool);
        }));

        JsonObject detect = new JsonObject();
        detect.addProperty("name", "file.detect");
        detect.addProperty("description", "Detect the Jifa analysis namespace and metadata of a local file");
        detect.add("inputSchema", targetSchema());
        tools.add(detect);

        JsonObject result = new JsonObject();
        result.add("tools", tools);
        LOGGER.info("MCP tools listed: count={}", tools.size());
        return result;
    }

    public JsonObject call(String name, JsonObject arguments) {
        long startedAt = System.nanoTime();
        String target = arguments != null && arguments.has("target") ? arguments.get("target").toString() : "<missing>";
        LOGGER.info("MCP tool call started: tool={}, target={}, arguments={}", name, target,
                    arguments == null ? "{}" : GSON.toJson(arguments));
        JsonObject result;
        try {
            result = execute(name, arguments == null ? new JsonObject() : arguments);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            result = error(-32000, message);
            LOGGER.error("MCP tool call failed: tool={}, target={}, durationMs={}", name, target,
                         elapsedMillis(startedAt), e);
            return result;
        }
        if (result.has("error")) {
            LOGGER.warn("MCP tool call rejected: tool={}, target={}, durationMs={}, error={}", name, target,
                        elapsedMillis(startedAt), GSON.toJson(result.get("error")));
        } else {
            LOGGER.info("MCP tool call completed: tool={}, target={}, durationMs={}, resultLength={}", name, target,
                        elapsedMillis(startedAt), GSON.toJson(result).length());
        }
        return result;
    }

    private JsonObject execute(String name, JsonObject arguments) throws Exception {
        if ("file.detect".equals(name)) return detect(arguments);
        int separator = name.indexOf('.');
        if (separator <= 0) return error(-32602, "tool name must be namespace.api");

        String namespace = name.substring(0, separator);
        String apiName = name.substring(separator + 1);
        Api api = apiService.supportedApis().getOrDefault(namespace, Set.of()).stream()
                           .filter(candidate -> candidate.name().equals(apiName) || candidate.aliases().contains(apiName))
                           .findFirst().orElse(null);
        if (api == null) return error(-32602, "unsupported tool: " + name);

        Path target = safeTarget(arguments.get("target"));
        Object[] params = resolveParameters(api, target, arguments);
        Object value = apiService.execute(target, namespace, apiName, params).join();
        return content(GSON.toJson(value));
    }

    private JsonObject detect(JsonObject arguments) throws Exception {
        Path target = safeTarget(arguments.get("target"));
        byte[] content = Files.readAllBytes(target);
        JsonObject diagnosis = new JsonObject();
        diagnosis.addProperty("target", target.toString());
        diagnosis.addProperty("size", content.length);
        diagnosis.addProperty("namespace", apiService.deduceNamespaceByContent(content));
        return content(GSON.toJson(diagnosis));
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private Path safeTarget(JsonElement element) {
        Validate.isTrue(element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString(),
                        "target is required");
        Path target = Path.of(element.getAsString()).toAbsolutePath().normalize();
        Validate.isTrue(target.startsWith(allowedRoot), "target is outside the allowed root");
        Validate.isTrue(Files.isRegularFile(target), "target is not a readable file");
        return target;
    }

    private Object[] resolveParameters(Api api, Path target, JsonObject arguments) throws ClassNotFoundException {
        ApiParameter[] definitions = api.parameters();
        Object[] result = new Object[definitions.length];
        for (int i = 0; i < definitions.length; i++) {
            ApiParameter definition = definitions[i];
            JsonElement value = arguments.get(definition.name());
            if (definition.targetPath()) {
                result[i] = target;
            } else if (definition.comparisonTargetPath()) {
                result[i] = safeTarget(value);
            } else {
                Validate.isTrue(value != null || !definition.required(), "missing parameter: " + definition.name());
                result[i] = value == null ? null : convert(value, definition.type());
            }
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object convert(JsonElement value, Type type) throws ClassNotFoundException {
        String name = type.getTypeName();
        if (name.equals(String.class.getTypeName())) return value.getAsString();
        if (name.equals(boolean.class.getTypeName()) || name.equals(Boolean.class.getTypeName())) return value.getAsBoolean();
        if (name.equals(int.class.getTypeName()) || name.equals(Integer.class.getTypeName())) return value.getAsInt();
        if (name.equals(long.class.getTypeName()) || name.equals(Long.class.getTypeName())) return value.getAsLong();
        if (name.equals(double.class.getTypeName()) || name.equals(Double.class.getTypeName())) return value.getAsDouble();
        Class<?> clazz = Class.forName(name);
        if (clazz.isEnum()) return Enum.valueOf((Class<? extends Enum>) clazz, value.getAsString());
        return GSON.fromJson(value, clazz);
    }

    private JsonObject schema(Api api) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (ApiParameter parameter : api.parameters()) {
            JsonObject property = new JsonObject();
            property.addProperty("type", jsonType(parameter.type()));
            properties.add(parameter.name(), property);
            if (parameter.required()) required.add(parameter.name());
        }
        schema.add("properties", properties);
        if (required.size() > 0) schema.add("required", required);
        return schema;
    }

    private JsonObject targetSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonObject target = new JsonObject();
        target.addProperty("type", "string");
        properties.add("target", target);
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("target");
        schema.add("required", required);
        return schema;
    }

    private String jsonType(Type type) {
        String name = type.getTypeName();
        if (name.equals(boolean.class.getTypeName()) || name.equals(Boolean.class.getTypeName())) return "boolean";
        if (name.equals(int.class.getTypeName()) || name.equals(Integer.class.getTypeName()) ||
            name.equals(long.class.getTypeName()) || name.equals(Long.class.getTypeName()) ||
            name.equals(double.class.getTypeName()) || name.equals(Double.class.getTypeName())) return "number";
        return "string";
    }

    private JsonObject content(String text) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "text");
        item.addProperty("text", text);
        JsonArray contents = new JsonArray();
        contents.add(item);
        JsonObject result = new JsonObject();
        result.add("content", contents);
        return result;
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
