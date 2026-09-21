package com.resumethinking.platform.profiles;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ScanLlmProfileCommand(
        @NotBlank @Size(max = 2048) String endpointUrl,
        @NotBlank @Size(max = 4096) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String apiKey) {
    @Override
    public String toString() {
        return "ScanLlmProfileCommand[redacted]";
    }
}
