package com.chitthi.client.sarvam.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TranslateRequest(
        @JsonProperty("input") String input,
        @JsonProperty("source_language_code") String sourceLanguageCode,
        @JsonProperty("target_language_code") String targetLanguageCode,
        @JsonProperty("mode") String mode,
        @JsonProperty("model") String model
) {
    public static TranslateRequest toEnglish(String input, String sourceLanguageCode) {
        return new TranslateRequest(input, sourceLanguageCode, "en-IN", "formal", "sarvam-translate:v1");
    }
}
