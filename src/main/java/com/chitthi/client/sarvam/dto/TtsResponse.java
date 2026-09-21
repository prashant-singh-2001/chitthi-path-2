package com.chitthi.client.sarvam.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TtsResponse(
        @JsonProperty("audios") List<String> audios
) {}
