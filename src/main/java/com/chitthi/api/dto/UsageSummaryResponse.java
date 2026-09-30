package com.chitthi.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record UsageSummaryResponse(
        String ownerId,
        UUID documentId,
        int dailyWordsProcessed,
        int dailyWordCap,
        int dailyWordsRemaining,
        boolean capExceeded,
        BigDecimal totalEstimatedCostInr,
        long totalCallsCount,
        BigDecimal totalCharactersProcessed,
        BigDecimal totalPagesProcessed,
        List<EndpointUsageBreakdown> endpointBreakdown,
        List<ApiCallSummary> recentCalls
) {}
