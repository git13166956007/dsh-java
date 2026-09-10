package io.github.git13166956007.dsh.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

record McpServerSecrets(Map<String, String> headers, Map<String, String> environment) {
    McpServerSecrets {
        headers = copy(headers);
        environment = copy(environment);
    }

    static McpServerSecrets empty() {
        return new McpServerSecrets(Map.of(), Map.of());
    }

    private static Map<String, String> copy(Map<String, String> values) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (values != null) {
            values.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                    result.put(key.trim(), value);
                }
            });
        }
        return Map.copyOf(result);
    }
}
