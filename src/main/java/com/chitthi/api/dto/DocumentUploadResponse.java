package com.chitthi.api.dto;

import java.time.Instant;
import java.util.UUID;

public record DocumentUploadResponse(
        UUID documentId,
        String title,
        String language,
        String status,
        int totalPages,
        int totalBatches,
        Instant createdAt
) {}
