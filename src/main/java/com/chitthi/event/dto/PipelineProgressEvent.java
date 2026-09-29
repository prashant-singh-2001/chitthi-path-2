package com.chitthi.event.dto;

import java.time.Instant;
import java.util.UUID;

public record PipelineProgressEvent(
        UUID documentId,
        Integer pageNo,
        String stage,
        String status,
        String message,
        Instant timestamp
) {
    public static PipelineProgressEvent of(UUID documentId, Integer pageNo, String stage, String status, String message) {
        return new PipelineProgressEvent(documentId, pageNo, stage, status, message, Instant.now());
    }
}
