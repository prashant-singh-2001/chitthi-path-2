package com.chitthi.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DocumentDetailResponse(
        UUID id,
        String ownerId,
        String title,
        String language,
        String status,
        Integer year,
        List<String> tags,
        List<PageSummaryResponse> pages,
        Instant createdAt,
        Instant updatedAt
) {}
