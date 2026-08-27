package com.resumethinking.platform.matching;

import java.util.*;

public interface CallbackReceiptRepository {
    Optional<CallbackReceipt> findByCallbackId(UUID callbackId);
    CallbackReceipt save(CallbackReceipt receipt);
    default CallbackReceipt saveAndFlush(CallbackReceipt receipt) { return save(receipt); }
    final class InMemory implements CallbackReceiptRepository {
        private final Map<UUID, CallbackReceipt> values = new LinkedHashMap<>();
        public synchronized Optional<CallbackReceipt> findByCallbackId(UUID id) { return Optional.ofNullable(values.get(id)); }
        public synchronized CallbackReceipt save(CallbackReceipt receipt) { values.put(receipt.callbackId(), receipt); return receipt; }
        public synchronized CallbackReceipt saveAndFlush(CallbackReceipt receipt) { return save(receipt); }
    }
}
