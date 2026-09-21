package com.chitthi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sarvam")
public record SarvamProperties(
        String baseUrl,
        String apiSubscriptionKey,
        long docAiPollIntervalMs,
        int docAiMaxPollAttempts
) {}
