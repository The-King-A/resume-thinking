package com.resumethinking.platform.ids;

public enum BusinessIdType {
    USER("user"), PROFILE("profile"), RESUME("resume"), TASK("task"),
    EVIDENCE("evidence"), RESULT("result"), CALLBACK("callback"), AUDIT("audit");

    public final String prefix;

    BusinessIdType(String prefix) {
        this.prefix = prefix;
    }
}
