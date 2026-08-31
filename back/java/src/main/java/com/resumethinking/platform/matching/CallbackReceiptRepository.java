package com.resumethinking.platform.matching;

import java.util.*;

public interface CallbackReceiptRepository {
    Optional<CallbackReceipt> findByCallbackId(String callbackId);
    CallbackReceipt save(CallbackReceipt receipt);
    default CallbackReceipt saveAndFlush(CallbackReceipt receipt) { return save(receipt); }
    final class InMemory implements CallbackReceiptRepository {
        private final Map<String, CallbackReceipt> values = new LinkedHashMap<>();
        public synchronized Optional<CallbackReceipt> findByCallbackId(String id) { return Optional.ofNullable(values.get(id)); }
        public synchronized CallbackReceipt save(CallbackReceipt receipt) { values.put(receipt.callbackId(), receipt); return receipt; }
        public synchronized CallbackReceipt saveAndFlush(CallbackReceipt receipt) { return save(receipt); }
    }
}
