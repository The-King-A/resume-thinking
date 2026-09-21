package com.resumethinking.platform.interviews;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

final class InterviewCallbackPayloadHash {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private InterviewCallbackPayloadHash() { }

    static String compute(InterviewAnalysisCallbackRequest request) {
        try {
            ObjectNode object = MAPPER.valueToTree(request);
            object.remove("payloadHash");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalize(object).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalArgumentException("VALIDATION_ERROR", exception);
        }
    }

    private static String canonicalize(JsonNode node) {
        if (node == null || node.isNull()) return "null";
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            StringBuilder result = new StringBuilder("{");
            for (int index = 0; index < names.size(); index++) {
                if (index > 0) result.append(',');
                result.append(jsonString(names.get(index))).append(':').append(canonicalize(node.get(names.get(index))));
            }
            return result.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder result = new StringBuilder("[");
            for (int index = 0; index < node.size(); index++) {
                if (index > 0) result.append(',');
                result.append(canonicalize(node.get(index)));
            }
            return result.append(']').toString();
        }
        if (node.isTextual()) return jsonString(node.textValue());
        if (node.isBoolean()) return node.booleanValue() ? "true" : "false";
        if (node.isNumber()) return canonicalNumber(node);
        throw new IllegalArgumentException("unsupported JSON value");
    }

    private static String jsonString(String value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException("invalid JSON string", exception); }
    }

    private static String canonicalNumber(JsonNode node) {
        BigDecimal value = node.isFloatingPointNumber() ? BigDecimal.valueOf(node.doubleValue()) : node.decimalValue();
        if (value.signum() == 0) return "0";
        value = value.stripTrailingZeros();
        BigDecimal absolute = value.abs();
        if (absolute.compareTo(BigDecimal.valueOf(1e-6)) >= 0 && absolute.compareTo(BigDecimal.valueOf(1e21)) < 0) {
            return value.toPlainString();
        }
        return value.toString().replace('E', 'e');
    }
}
