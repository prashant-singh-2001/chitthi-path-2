package com.chitthi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "sarvam")
public record SarvamProperties(
        @DefaultValue("https://api.sarvam.ai") String baseUrl,
        String apiSubscriptionKey,
        @DefaultValue("5000") long docAiPollIntervalMs,
        @DefaultValue("30") int docAiMaxPollAttempts,
        @DefaultValue("5000") long connectTimeoutMs,
        @DefaultValue("30000") long readTimeoutMs,
        @DefaultValue("/doc-ai/v1/job/digitise") String docAiEndpoint,
        @DefaultValue("/doc-ai/v1/job/{jobId}/status") String docAiStatusEndpoint,
        @DefaultValue("/translate") String translateEndpoint,
        @DefaultValue("/text-to-speech") String ttsEndpoint,
        @DefaultValue("shubh") String ttsSpeaker,
        @DefaultValue("bulbul:v3") String ttsModel,
        @DefaultValue("sarvam-translate:v1") String translateModel
) {}
