package com.chitthi.client.sarvam.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DigitiseJobResponse(
        @JsonProperty("job_id") String jobId
) {}
