package com.resumethinking.platform;

import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic readable business IDs for unit-test fixtures. */
public final class TestIds {
    private static final AtomicInteger USERS = new AtomicInteger();
    private static final AtomicInteger PROFILES = new AtomicInteger();
    private static final AtomicInteger RESUMES = new AtomicInteger();
    private static final AtomicInteger TASKS = new AtomicInteger();
    private static final AtomicInteger EVIDENCE = new AtomicInteger();
    private static final AtomicInteger CALLBACKS = new AtomicInteger();
    private static final AtomicInteger REQUIREMENTS = new AtomicInteger();
    private static final AtomicInteger SUGGESTIONS = new AtomicInteger();
    private static final AtomicInteger SESSIONS = new AtomicInteger();
    private static final AtomicInteger QUESTIONS = new AtomicInteger();
    private static final AtomicInteger ANSWERS = new AtomicInteger();
    private static final AtomicInteger FEEDBACK = new AtomicInteger();
    private static final AtomicInteger CONFIRMATIONS = new AtomicInteger();

    private TestIds() {}

    public static String user() { return next("user", USERS); }
    public static String profile() { return next("profile", PROFILES); }
    public static String resume() { return next("resume", RESUMES); }
    public static String task() { return next("task", TASKS); }
    public static String evidence() { return next("evidence", EVIDENCE); }
    public static String callback() { return next("callback", CALLBACKS); }
    public static String requirement() { return next("requirement", REQUIREMENTS); }
    public static String suggestion() { return next("suggestion", SUGGESTIONS); }
    public static String session() { return next("session", SESSIONS); }
    public static String question() { return next("question", QUESTIONS); }
    public static String answer() { return next("answer", ANSWERS); }
    public static String feedback() { return next("feedback", FEEDBACK); }
    public static String confirmation() { return next("confirmation", CONFIRMATIONS); }

    private static String next(String prefix, AtomicInteger counter) {
        return prefix + String.format("%03d", counter.incrementAndGet());
    }
}
