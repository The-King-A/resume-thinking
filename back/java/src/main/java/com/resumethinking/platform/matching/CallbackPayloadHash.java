package com.resumethinking.platform.matching;
import com.fasterxml.jackson.databind.*; import java.nio.charset.StandardCharsets; import java.security.MessageDigest; import java.util.*;
final class CallbackPayloadHash {
 private static final ObjectMapper MAPPER=new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY,true);
 static String compute(AnalysisCallbackRequest r){try{Map<String,Object> m=new TreeMap<>(); m.put("attempt",r.attempt());m.put("callbackId",r.callbackId());m.put("callbackToken",r.callbackToken());m.put("correlationId",r.correlationId());m.put("outcome",r.outcome());m.put("taskId",r.taskId());if(r.result()!=null)m.put("result",r.result());if(r.errorCode()!=null)m.put("errorCode",r.errorCode());byte[] b=MAPPER.writeValueAsBytes(m);return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(Exception e){throw new IllegalArgumentException("VALIDATION_ERROR",e);}}
}
