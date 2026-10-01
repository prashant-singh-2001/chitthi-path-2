package com.chitthi.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ShareLinkResponse(
        UUID documentId,
        String title,
        String shareUrl,
        String token,
        Instant expiresAt
) {}
