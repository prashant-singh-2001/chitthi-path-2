package com.chitthi.client.sarvam.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DigitiseJobStatusResponse(
        @JsonProperty("job_id") String jobId,
        @JsonProperty("status") String status,
        @JsonProperty("download_url") String downloadUrl,
        @JsonProperty("error_message") String errorMessage
) {
    public boolean isCompleted() {
        return "completed".equalsIgnoreCase(status);
    }

    public boolean isFailed() {
        return "failed".equalsIgnoreCase(status) || "rejected".equalsIgnoreCase(status);
    }

    public boolean isPartiallyCompleted() {
        return "partially_completed".equalsIgnoreCase(status);
    }
}
