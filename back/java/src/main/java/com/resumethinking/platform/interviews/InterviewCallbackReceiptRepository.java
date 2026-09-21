package com.resumethinking.platform.interviews;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public interface InterviewCallbackReceiptRepository {
    InterviewCallbackReceipt save(InterviewCallbackReceipt receipt);
    Optional<InterviewCallbackReceipt> findByCallbackId(String callbackId);

    final class InMemory implements InterviewCallbackReceiptRepository {
        private final Map<String, InterviewCallbackReceipt> values = new LinkedHashMap<>();
        public synchronized InterviewCallbackReceipt save(InterviewCallbackReceipt value) { values.put(value.callbackId(), value); return value; }
        public synchronized Optional<InterviewCallbackReceipt> findByCallbackId(String id) { return Optional.ofNullable(values.get(id)); }
    }
}
