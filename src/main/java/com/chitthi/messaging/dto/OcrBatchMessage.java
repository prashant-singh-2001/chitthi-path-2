package com.chitthi.messaging.dto;

import java.util.UUID;

public record OcrBatchMessage(
        UUID batchId,
        UUID documentId,
        String pageRange,
        String storageKey,
        String languageCode
) {}
