package com.chitthi.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ApiCallSummary(
        UUID id,
        UUID documentId,
        String endpoint,
        BigDecimal units,
        String unitType,
        Long latencyMs,
        Integer httpStatus,
        BigDecimal estCostInr,
        Instant createdAt
) {}
