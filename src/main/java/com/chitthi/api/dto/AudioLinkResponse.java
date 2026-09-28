package com.chitthi.api.dto;

import java.util.UUID;

public record AudioLinkResponse(
        UUID documentId,
        String language,
        String audioUrl
) {}
