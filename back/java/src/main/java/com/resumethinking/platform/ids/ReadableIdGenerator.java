package com.resumethinking.platform.ids;

import java.util.Objects;
import java.util.regex.Pattern;

public interface ReadableIdGenerator {
    Pattern SEQUENCE_PATTERN = Pattern.compile("[0-9]+");

    String next(BusinessIdType type);

    static String format(BusinessIdType type, long sequence) {
        Objects.requireNonNull(type, "business id type must not be null");
        if (sequence < 1) {
            throw new IllegalArgumentException("business id sequence must be at least 1");
        }
        String value = type.prefix + String.format(java.util.Locale.ROOT, "%03d", sequence);
        validate(type, value);
        return value;
    }

    static void validate(BusinessIdType type, String value) {
        Objects.requireNonNull(type, "business id type must not be null");
        if (value == null || value.isEmpty() || value.length() > 64
                || !value.startsWith(type.prefix)) {
            throw new IllegalArgumentException("invalid " + type.prefix + " business id");
        }
        String suffix = value.substring(type.prefix.length());
        if (suffix.length() < 3 || !SEQUENCE_PATTERN.matcher(suffix).matches()
                || Long.parseLong(suffix) < 1) {
            throw new IllegalArgumentException("invalid " + type.prefix + " business id");
        }
    }

    static void validate(String value, BusinessIdType type) {
        validate(type, value);
    }

    static boolean isValid(BusinessIdType type, String value) {
        try {
            validate(type, value);
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }
}
