package com.resumethinking.platform.matching;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** RFC 8785 canonical JSON for the callback envelope. */
final class CallbackPayloadHash {
 private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

 static String compute(AnalysisCallbackRequest request) {
  try {
   ObjectNode object = MAPPER.createObjectNode();
   object.put("taskId", request.taskId().toString());
   object.put("attempt", request.attempt());
   object.put("callbackId", request.callbackId().toString());
   object.put("callbackToken", request.callbackToken());
   object.put("outcome", request.outcome());
   object.put("correlationId", request.correlationId().toString());
   if (request.result() != null) object.set("result", MAPPER.valueToTree(request.result()));
   if (request.errorCode() != null) object.put("errorCode", request.errorCode());
   String canonical = canonicalize(object);
   byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
   return HexFormat.of().formatHex(digest);
  } catch (Exception e) {
   throw new IllegalArgumentException("VALIDATION_ERROR", e);
  }
 }

 private static String canonicalize(JsonNode node) {
  if (node == null || node.isNull()) return "null";
  if (node.isObject()) {
   StringBuilder out = new StringBuilder("{");
   List<String> names = new ArrayList<>(); node.fieldNames().forEachRemaining(names::add); names.sort(String::compareTo);
   for (int i = 0; i < names.size(); i++) {
    if (i > 0) out.append(',');
    out.append(jsonString(names.get(i))).append(':').append(canonicalize(node.get(names.get(i))));
   }
   return out.append('}').toString();
  }
  if (node.isArray()) {
   StringBuilder out = new StringBuilder("[");
   for (int i = 0; i < node.size(); i++) { if (i > 0) out.append(','); out.append(canonicalize(node.get(i))); }
   return out.append(']').toString();
  }
  if (node.isTextual()) return jsonString(node.textValue());
  if (node.isBoolean()) return node.booleanValue() ? "true" : "false";
  if (node.isNumber()) return canonicalNumber(node);
  throw new IllegalArgumentException("unsupported JSON value");
 }

 private static String jsonString(String value) {
  try { return MAPPER.writeValueAsString(value); }
  catch (Exception e) { throw new IllegalArgumentException("invalid JSON string", e); }
 }

 private static String canonicalNumber(JsonNode node) {
  BigDecimal decimal;
  if (node.isFloatingPointNumber()) decimal = BigDecimal.valueOf(node.doubleValue());
  else decimal = node.decimalValue();
  if (decimal.signum() == 0) return "0";
  decimal = decimal.stripTrailingZeros();
  BigDecimal abs = decimal.abs();
  if (abs.compareTo(BigDecimal.valueOf(1e-6)) >= 0 && abs.compareTo(BigDecimal.valueOf(1e21)) < 0)
   return decimal.toPlainString();
  String scientific = decimal.toString().replace('E', 'e');
  int e = scientific.indexOf('e');
  if (e < 0) return scientific;
  String mantissa = scientific.substring(0, e);
  String exponent = scientific.substring(e + 1);
  if (mantissa.endsWith(".0")) mantissa = mantissa.substring(0, mantissa.length() - 2);
  int sign = 0;
  if (exponent.startsWith("+")) { sign = 1; exponent = exponent.substring(1); }
  else if (exponent.startsWith("-")) { sign = -1; exponent = exponent.substring(1); }
  exponent = exponent.replaceFirst("^0+(?!$)", "");
  return mantissa + "e" + (sign < 0 ? "-" : "+") + exponent;
 }
}
