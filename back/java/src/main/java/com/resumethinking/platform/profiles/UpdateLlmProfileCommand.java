package com.resumethinking.platform.profiles;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Profile updates deliberately make the API key optional.  An omitted key
 * means "keep the encrypted key already stored"; there is no API operation
 * that returns or clears key material.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record UpdateLlmProfileCommand(
        @NotBlank @Size(max = 100) String displayName,
        @NotBlank @Size(max = 2048) String endpointUrl,
        @NotBlank @Size(max = 200) String modelName,
        @Size(min = 1, max = 4096) String apiKey,
        boolean selected) {

    public UpdateLlmProfileCommand(String displayName, String endpointUrl, String modelName, String apiKey) {
        this(displayName, endpointUrl, modelName, apiKey, false);
    }
}
