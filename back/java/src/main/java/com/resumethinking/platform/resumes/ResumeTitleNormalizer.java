package com.resumethinking.platform.resumes;

import java.util.Locale;

/** Canonical application-owned key for same-owner effective-title uniqueness. */
public final class ResumeTitleNormalizer {
    private ResumeTitleNormalizer() {
    }

    public static String normalize(String title) {
        if (title == null) throw new IllegalArgumentException("VALIDATION_ERROR");
        int start = 0;
        int end = title.length();
        while (start < end) {
            int codePoint = title.codePointAt(start);
            if (!isSurroundingWhitespace(codePoint)) break;
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = title.codePointBefore(end);
            if (!isSurroundingWhitespace(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        String normalized = title.substring(start, end).toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw new IllegalArgumentException("VALIDATION_ERROR");
        }
        return normalized;
    }

    private static boolean isSurroundingWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
