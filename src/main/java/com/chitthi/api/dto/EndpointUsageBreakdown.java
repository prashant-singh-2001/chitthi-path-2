package com.chitthi.api.dto;

import java.math.BigDecimal;

public record EndpointUsageBreakdown(
        String endpoint,
        long callCount,
        BigDecimal totalUnits,
        String unitType,
        BigDecimal totalCostInr,
        double avgLatencyMs,
        long minLatencyMs,
        long maxLatencyMs
) {}
