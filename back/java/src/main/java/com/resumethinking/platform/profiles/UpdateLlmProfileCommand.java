package com.resumethinking.platform.profiles;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Profile updates deliberately make the API key optional. An omitted or blank
 * key means "keep the encrypted key already stored"; there is no API operation
 * that returns or clears key material.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public final class UpdateLlmProfileCommand {
    @NotBlank @Size(max = 100) private String displayName;
    @NotBlank @Size(max = 2048) private String endpointUrl;
    @NotBlank @Size(max = 200) private String modelName;
    @Size(max = 4096) private String apiKey;
    private boolean selected;

    public UpdateLlmProfileCommand() {
    }

    public UpdateLlmProfileCommand(String displayName, String endpointUrl, String modelName, String apiKey, boolean selected) {
        this.displayName = displayName;
        this.endpointUrl = endpointUrl;
        this.modelName = modelName;
        this.apiKey = apiKey;
        this.selected = selected;
    }

    public UpdateLlmProfileCommand(String displayName, String endpointUrl, String modelName, String apiKey) {
        this(displayName, endpointUrl, modelName, apiKey, false);
    }

    public String displayName() { return displayName; }
    public String endpointUrl() { return endpointUrl; }
    public String modelName() { return modelName; }
    public String apiKey() { return apiKey; }
    public boolean selected() { return selected; }

    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setEndpointUrl(String endpointUrl) { this.endpointUrl = endpointUrl; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    @JsonSetter(value = "apiKey", nulls = Nulls.FAIL)
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public void setSelected(boolean selected) { this.selected = selected; }
}
