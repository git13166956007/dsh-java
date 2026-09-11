package io.github.git13166956007.dsh.tool;

import java.util.Iterator;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** Validates the JSON Schema subset used by model tool calls. */
final class ToolSchemaValidator {
    private ToolSchemaValidator() { }

    static void validate(JsonNode schema, JsonNode value) {
        validateNode(schema, value, "$", true);
    }

    private static void validateNode(JsonNode schema, JsonNode value, String path, boolean root) {
        if (schema == null || schema.isNull()) return;
        if (value == null || value.isMissingNode() || value.isNull()) {
            if (schema.path("type").asString("").equals("null")) return;
            throw invalid(path, "value is required");
        }
        JsonNode enumValues = schema.path("enum");
        if (enumValues.isArray() && !contains(enumValues, value)) {
            throw invalid(path, "value is not in enum");
        }
        String type = schema.path("type").asString(null);
        if (type != null && !matchesType(type, value)) {
            throw invalid(path, "expected " + type + " but got " + valueType(value));
        }
        switch (type == null ? "" : type) {
            case "object" -> validateObject(schema, value, path);
            case "array" -> validateArray(schema, value, path);
            case "string" -> validateString(schema, value, path);
            case "number", "integer" -> validateNumber(schema, value, path);
            default -> { }
        }
    }

    private static void validateObject(JsonNode schema, JsonNode value, String path) {
        JsonNode properties = schema.path("properties");
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            for (JsonNode name : required) {
                if (!value.has(name.asString())) throw invalid(path, "missing required property " + name.asString());
            }
        }
        boolean additionalAllowed = !schema.has("additionalProperties") || schema.path("additionalProperties").asBoolean(true);
        Iterator<Map.Entry<String, JsonNode>> fields = value.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode propertySchema = properties.path(field.getKey());
            if (propertySchema.isMissingNode()) {
                if (!additionalAllowed) throw invalid(path, "unknown property " + field.getKey());
                continue;
            }
            validateNode(propertySchema, field.getValue(), path + "." + field.getKey(), false);
        }
    }

    private static void validateArray(JsonNode schema, JsonNode value, String path) {
        JsonNode items = schema.path("items");
        if (!items.isMissingNode()) {
            for (int index = 0; index < value.size(); index++) {
                validateNode(items, value.get(index), path + "[" + index + "]", false);
            }
        }
        if (schema.has("minItems") && value.size() < schema.path("minItems").asInt()) {
            throw invalid(path, "fewer items than minItems");
        }
        if (schema.has("maxItems") && value.size() > schema.path("maxItems").asInt()) {
            throw invalid(path, "more items than maxItems");
        }
    }

    private static void validateString(JsonNode schema, JsonNode value, String path) {
        if (schema.has("minLength") && value.asString().length() < schema.path("minLength").asInt()) {
            throw invalid(path, "shorter than minLength");
        }
        if (schema.has("maxLength") && value.asString().length() > schema.path("maxLength").asInt()) {
            throw invalid(path, "longer than maxLength");
        }
    }

    private static void validateNumber(JsonNode schema, JsonNode value, String path) {
        if (schema.has("minimum") && value.asDouble() < schema.path("minimum").asDouble()) {
            throw invalid(path, "less than minimum");
        }
        if (schema.has("maximum") && value.asDouble() > schema.path("maximum").asDouble()) {
            throw invalid(path, "greater than maximum");
        }
    }

    private static boolean matchesType(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isString();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    private static boolean contains(JsonNode values, JsonNode value) {
        for (JsonNode candidate : values) if (candidate.equals(value)) return true;
        return false;
    }

    private static String valueType(JsonNode value) {
        if (value.isObject()) return "object";
        if (value.isArray()) return "array";
        if (value.isTextual()) return "string";
        if (value.isBoolean()) return "boolean";
        if (value.isNumber()) return "number";
        return "value";
    }

    private static IllegalArgumentException invalid(String path, String message) {
        return new IllegalArgumentException("invalid tool arguments at " + path + ": " + message);
    }
}
