package com.chitthi.api.dto;

import java.util.UUID;

public record PageSummaryResponse(
        UUID id,
        int pageNo,
        String status,
        boolean edited,
        String originalText,
        String translatedText,
        String imageUrl,
        String rawImageUrl,
        boolean preprocessed
) {
    public PageSummaryResponse(UUID id, int pageNo, String status, boolean edited, String originalText, String translatedText, String imageUrl) {
        this(id, pageNo, status, edited, originalText, translatedText, imageUrl, null, false);
    }
}
