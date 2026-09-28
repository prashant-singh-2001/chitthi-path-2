package com.chitthi.messaging.dto;

import java.util.UUID;

public record PageTtsMessage(
        UUID pageId,
        UUID documentId,
        int pageNo,
        String languageCode,
        String textHash
) {}
