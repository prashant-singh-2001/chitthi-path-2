package com.chitthi.client.sarvam.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TtsRequest(
        @JsonProperty("inputs") List<String> inputs,
        @JsonProperty("target_language_code") String targetLanguageCode,
        @JsonProperty("speaker") String speaker,
        @JsonProperty("pitch") Double pitch,
        @JsonProperty("pace") Double pace,
        @JsonProperty("loudness") Double loudness,
        @JsonProperty("speech_sample_rate") Integer speechSampleRate,
        @JsonProperty("enable_preprocessing") Boolean enablePreprocessing,
        @JsonProperty("model") String model
) {
    public static TtsRequest create(List<String> texts, String targetLanguageCode) {
        return new TtsRequest(
                texts,
                targetLanguageCode,
                "meera",
                0.0,
                1.0,
                1.0,
                22050,
                true,
                "bulbul:v3"
        );
    }
}
