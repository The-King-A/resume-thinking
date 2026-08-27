package com.resumethinking.platform.matching;

/**
 * Supported job families for the released matching slice.
 *
 * <p>The enum is intentionally narrow today. Adding another family is a
 * contract and prompt change, so callers cannot silently submit an
 * unsupported role by changing only the job description text.</p>
 */
public enum JobFamily {
    JAVA_BACKEND
}
