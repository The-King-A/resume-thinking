package com.resumethinking.platform.profiles;
import java.time.Instant; import java.util.List;
public record LlmProfileTestResponse(boolean available, Instant testedAt, List<String> models, String diagnostic) {}
