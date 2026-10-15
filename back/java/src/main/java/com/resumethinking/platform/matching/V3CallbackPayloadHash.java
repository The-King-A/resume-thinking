package com.resumethinking.platform.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.resumethinking.platform.ids.BusinessIdType;
import com.resumethinking.platform.ids.ReadableIdGenerator;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** RFC 8785 canonical hash for the v3 callback, including revision identity. */
final class V3CallbackPayloadHash {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    static String compute(V3AnalysisCallbackRequest request) {
        try {
            if (request == null || request.callbackToken() == null || request.outcome() == null
                    || request.correlationId() == null) {
                throw new IllegalArgumentException("callback envelope is incomplete");
            }
            ObjectNode object = MAPPER.createObjectNode();
            object.put("taskId", requireBusinessId(BusinessIdType.TASK, request.taskId()));
            object.put("revisionId", requireBusinessId(BusinessIdType.REVISION, request.revisionId()));
            object.put("attempt", request.attempt());
            object.put("callbackId", requireBusinessId(BusinessIdType.CALLBACK, request.callbackId()));
            object.put("callbackToken", request.callbackToken());
            object.put("outcome", request.outcome());
            object.put("correlationId", request.correlationId().toString());
            if (request.result() != null) object.set("result", MAPPER.valueToTree(request.result()));
            if (request.errorCode() != null) object.put("errorCode", request.errorCode());
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalize(object).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalArgumentException("VALIDATION_ERROR", exception);
        }
    }

    private static String requireBusinessId(BusinessIdType type, String value) {
        if (!ReadableIdGenerator.isValid(type, value)) {
            throw new IllegalArgumentException("invalid " + type.prefix + " business id");
        }
        return value;
    }

    private static String canonicalize(JsonNode node) {
        if (node == null || node.isNull()) return "null";
        if (node.isObject()) {
            StringBuilder out = new StringBuilder("{");
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (int index = 0; index < names.size(); index++) {
                if (index > 0) out.append(',');
                out.append(jsonString(names.get(index))).append(':')
                        .append(canonicalize(node.get(names.get(index))));
            }
            return out.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder out = new StringBuilder("[");
            for (int index = 0; index < node.size(); index++) {
                if (index > 0) out.append(',');
                out.append(canonicalize(node.get(index)));
            }
            return out.append(']').toString();
        }
        if (node.isTextual()) return jsonString(node.textValue());
        if (node.isBoolean()) return node.booleanValue() ? "true" : "false";
        if (node.isNumber()) return canonicalNumber(node);
        throw new IllegalArgumentException("unsupported JSON value");
    }

    private static String jsonString(String value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid JSON string", exception);
        }
    }

    private static String canonicalNumber(JsonNode node) {
        BigDecimal decimal = node.isFloatingPointNumber()
                ? BigDecimal.valueOf(node.doubleValue()) : node.decimalValue();
        if (decimal.signum() == 0) return "0";
        decimal = decimal.stripTrailingZeros();
        BigDecimal absolute = decimal.abs();
        if (absolute.compareTo(BigDecimal.valueOf(1e-6)) >= 0
                && absolute.compareTo(BigDecimal.valueOf(1e21)) < 0) {
            return decimal.toPlainString();
        }
        String scientific = decimal.toString().replace('E', 'e');
        int marker = scientific.indexOf('e');
        if (marker < 0) return scientific;
        String mantissa = scientific.substring(0, marker);
        String exponent = scientific.substring(marker + 1);
        int sign = 0;
        if (exponent.startsWith("+")) {
            sign = 1;
            exponent = exponent.substring(1);
        } else if (exponent.startsWith("-")) {
            sign = -1;
            exponent = exponent.substring(1);
        }
        exponent = exponent.replaceFirst("^0+(?!$)", "");
        return mantissa + "e" + (sign < 0 ? "-" : "+") + exponent;
    }
}
