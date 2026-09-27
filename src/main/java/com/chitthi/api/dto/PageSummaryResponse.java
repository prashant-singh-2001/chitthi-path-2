package com.chitthi.api.dto;

import java.util.UUID;

public record PageSummaryResponse(
        UUID id,
        int pageNo,
        String status,
        boolean edited,
        String originalText,
        String translatedText,
        String imageUrl
) {}
