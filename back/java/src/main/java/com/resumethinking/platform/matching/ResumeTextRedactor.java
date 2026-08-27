package com.resumethinking.platform.matching;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps Java callback validation aligned with the Python redaction boundary. */
final class ResumeTextRedactor {
    private static final Pattern NAME_PATTERN = Pattern.compile(
            "(?im)(?:(?<=^)|(?<=[\\r\\n]))[ \\t]*(?:[-*•][ \\t]*)?"
                    + "(?:姓名|名字|真实姓名|full[ \\t]+name|candidate[ \\t]+name|legal[ \\t]+name|name)"
                    + "[ \\t]*[:：][ \\t]*"
                    + "(?<value>"
                    + "(?:[\\u3400-\\u9fff]{2,6}(?:[·•][\\u3400-\\u9fff]{1,6})?)"
                    + "|(?:[A-Za-z][A-Za-z.'-]{1,39}(?:[ \\t]+[A-Za-z][A-Za-z.'-]{1,39}){0,3})"
                    + ")"
                    + "(?=[ \\t]*(?:$|[\\r\\n,，。.;；|/、()（）]))");

    private static final List<Rule> RULES = List.of(
            new Rule("email", Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"), "[REDACTED_EMAIL]"),
            new Rule("identity_number", Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)"), "[REDACTED_ID]"),
            new Rule("phone", Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)"), "[REDACTED_PHONE]"),
            new Rule("address", Pattern.compile("(?<!\\S)(?:(?:地址|住址)[:：]\\s*)?(?:北京|上海|天津|重庆|广东|浙江|江苏|四川|湖北|湖南|山东|福建|安徽|河北|河南|陕西|辽宁|吉林|黑龙江|江西|广西|云南|贵州|山西|甘肃|海南|新疆|西藏|内蒙古|宁夏|青海)省?(?:[^\\n,，。;；.．]{0,40})(?:路|街|道|号|室|区)(?=$|[\\s,，。;；.．])"), "[REDACTED_ADDRESS]")
    );

    private ResumeTextRedactor() {}

    /**
     * Returns a slice using original code-point offsets while replacing any
     * sensitive span that intersects the requested range.
     */
    static String redactedSlice(String text, int start, int end) {
        if (text == null || start < 0 || end < start || end > text.codePointCount(0, text.length())) {
            throw new IllegalArgumentException("invalid text range");
        }
        List<Replacement> replacements = replacements(text);
        if (replacements.isEmpty()) return slice(text, start, end);
        StringBuilder out = new StringBuilder();
        int cursor = start;
        for (Replacement replacement : replacements) {
            if (replacement.end() <= start) continue;
            if (replacement.start() >= end) break;
            int literalEnd = Math.min(replacement.start(), end);
            if (cursor < literalEnd) out.append(slice(text, cursor, literalEnd));
            out.append(replacement.value());
            cursor = Math.max(cursor, replacement.end());
            if (cursor >= end) break;
        }
        if (cursor < end) out.append(slice(text, cursor, end));
        return out.toString();
    }

    /** Returns the complete text after applying the same redaction rules. */
    static String redactedText(String text) {
        if (text == null) throw new IllegalArgumentException("text is required");
        return redactedSlice(text, 0, text.codePointCount(0, text.length()));
    }

    static boolean isRedacted(String text) {
        return text != null && redactedText(text).equals(text);
    }

    private static List<Replacement> replacements(String text) {
        List<Replacement> matches = new ArrayList<>();
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(text);
            while (matcher.find()) {
                matches.add(new Replacement(
                        text.codePointCount(0, matcher.start()),
                        text.codePointCount(0, matcher.end()),
                        rule.replacement()));
            }
        }
        Matcher nameMatcher = NAME_PATTERN.matcher(text);
        while (nameMatcher.find()) {
            matches.add(new Replacement(
                    text.codePointCount(0, nameMatcher.start("value")),
                    text.codePointCount(0, nameMatcher.end("value")),
                    "[REDACTED_NAME]"));
        }
        matches.sort(Comparator.comparingInt(Replacement::start)
                .thenComparing((left, right) -> Integer.compare(right.end() - right.start(), left.end() - left.start())));
        List<Replacement> selected = new ArrayList<>();
        for (Replacement candidate : matches) {
            if (!selected.isEmpty() && candidate.start() < selected.getLast().end()) continue;
            selected.add(candidate);
        }
        return selected;
    }

    private static String slice(String text, int start, int end) {
        return text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
    }

    private record Rule(String kind, Pattern pattern, String replacement) {}
    private record Replacement(int start, int end, String value) {}
}
