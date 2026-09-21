package com.resumethinking.platform.ids;

public enum BusinessIdType {
    USER("user"), PROFILE("profile"), RESUME("resume"), REVISION("revision"), TASK("task"),
    EVIDENCE("evidence"), RESULT("result"), CALLBACK("callback"), AUDIT("audit"),
    INTERVIEW_SESSION("session"), INTERVIEW_QUESTION("question"), INTERVIEW_ANSWER("answer"),
    INTERVIEW_FEEDBACK("feedback"), INTERVIEW_CONFIRMATION("confirmation");

    public final String prefix;

    BusinessIdType(String prefix) {
        this.prefix = prefix;
    }
}
