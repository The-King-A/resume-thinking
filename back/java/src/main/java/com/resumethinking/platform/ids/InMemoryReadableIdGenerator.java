package com.resumethinking.platform.ids;

import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicLong;

/** Test-only sequence generator. Production code must use JdbcReadableIdGenerator. */
public final class InMemoryReadableIdGenerator implements ReadableIdGenerator {
    private final EnumMap<BusinessIdType, AtomicLong> sequences = new EnumMap<>(BusinessIdType.class);

    public InMemoryReadableIdGenerator() {
        for (BusinessIdType type : BusinessIdType.values()) {
            sequences.put(type, new AtomicLong());
        }
    }

    @Override
    public String next(BusinessIdType type) {
        if (type == null) {
            throw new IllegalArgumentException("business id type must not be null");
        }
        return ReadableIdGenerator.format(type, sequences.get(type).incrementAndGet());
    }

    @Override
    public void ensureNextAtLeast(BusinessIdType type, long nextValue) {
        if (type == null) {
            throw new IllegalArgumentException("business id type must not be null");
        }
        if (nextValue < 1) {
            throw new IllegalArgumentException("business id sequence must be at least 1");
        }
        // The in-memory counter stores the last allocated suffix, whereas the
        // public operation is expressed in terms of the next suffix.
        sequences.get(type).updateAndGet(current -> Math.max(current, nextValue - 1));
    }
}
