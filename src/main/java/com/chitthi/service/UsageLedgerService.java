package com.chitthi.service;

import com.chitthi.api.dto.ApiCallSummary;
import com.chitthi.api.dto.EndpointUsageBreakdown;
import com.chitthi.api.dto.UsageSummaryResponse;
import com.chitthi.domain.entity.ApiCallEntity;
import com.chitthi.domain.entity.PageEntity;
import com.chitthi.repository.ApiCallRepository;
import com.chitthi.repository.PageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class UsageLedgerService {

    private static final Logger log = LoggerFactory.getLogger(UsageLedgerService.class);

    private final ApiCallRepository apiCallRepository;
    private final PageRepository pageRepository;
    private final int dailyWordCap;

    private final Counter totalSpendCounter;
    private final Counter wordsProcessedCounter;
    private final Counter ttsCacheHitsCounter;
    private final Counter ttsCacheMissesCounter;
    private final MeterRegistry meterRegistry;

    public UsageLedgerService(ApiCallRepository apiCallRepository,
                              PageRepository pageRepository,
                              @Value("${chitthi.pipeline.daily-word-cap:7000}") int dailyWordCap,
                              MeterRegistry meterRegistry) {
        this.apiCallRepository = apiCallRepository;
        this.pageRepository = pageRepository;
        this.dailyWordCap = dailyWordCap;
        this.meterRegistry = meterRegistry;

        this.totalSpendCounter = Counter.builder("chitthi.api.spend.total")
                .description("Total spend on external Sarvam APIs in INR")
                .baseUnit("INR")
                .register(meterRegistry);

        this.wordsProcessedCounter = Counter.builder("chitthi.words.processed.total")
                .description("Total Indic words processed")
                .register(meterRegistry);

        this.ttsCacheHitsCounter = Counter.builder("chitthi.tts.cache.hits")
                .description("Number of TTS cache hits")
                .register(meterRegistry);

        this.ttsCacheMissesCounter = Counter.builder("chitthi.tts.cache.misses")
                .description("Number of TTS cache misses")
                .register(meterRegistry);
    }

    /**
     * Tokenize and count Indic words using whitespace and Devanagari danda boundaries.
     */
    public int countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        // Normalize Devanagari dandas (।, ॥) and punctuation to whitespace
        String normalized = text.replaceAll("[।॥,.?!:;\"'()\\[\\]{}]", " ").trim();
        if (normalized.isBlank()) {
            return 0;
        }
        String[] tokens = normalized.split("\\s+");
        return tokens.length;
    }

    /**
     * Compute cumulative word count processed today by document owner.
     */
    @Transactional(readOnly = true)
    public int getDailyWordsProcessed(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            ownerId = "default";
        }
        java.time.Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<PageEntity> pages = pageRepository.findPagesByOwnerSince(ownerId, startOfDay);

        int totalWords = 0;
        for (PageEntity page : pages) {
            totalWords += countWords(page.getOriginalText());
        }
        return totalWords;
    }

    /**
     * Check if document owner has reached or exceeded the 7,000-word daily processing ceiling.
     */
    @Transactional(readOnly = true)
    public boolean isDailyCapExceeded(String ownerId) {
        return getDailyWordsProcessed(ownerId) >= dailyWordCap;
    }

    /**
     * Record an API call to the ledger and update Micrometer observability metrics.
     */
    @Transactional
    public ApiCallEntity recordApiCall(UUID documentId, String endpoint, BigDecimal units, String unitType,
                                       long latencyMs, int httpStatus, BigDecimal costInr) {
        ApiCallEntity apiCall = new ApiCallEntity(
                UUID.randomUUID(),
                documentId,
                endpoint,
                units,
                unitType,
                latencyMs,
                httpStatus,
                costInr
        );
        apiCall = apiCallRepository.save(apiCall);

        totalSpendCounter.increment(costInr.doubleValue());
        Timer.builder("chitthi.stage.latency")
                .tag("endpoint", endpoint)
                .register(meterRegistry)
                .record(Duration.ofMillis(latencyMs));

        log.debug("Recorded API call to {} for doc {}: units={}, cost=₹{}", endpoint, documentId, units, costInr);
        return apiCall;
    }

    public void recordWordsProcessed(int wordCount) {
        wordsProcessedCounter.increment(wordCount);
    }

    public void recordTtsCacheHit() {
        ttsCacheHitsCounter.increment();
    }

    public void recordTtsCacheMiss() {
        ttsCacheMissesCounter.increment();
    }

    public int getDailyWordCap() {
        return dailyWordCap;
    }

    /**
     * Retrieve aggregated usage, spend, and latency breakdown for an owner or specific document.
     */
    @Transactional(readOnly = true)
    public UsageSummaryResponse getUsageSummary(String ownerId, UUID documentId) {
        String effectiveOwner = (ownerId != null && !ownerId.isBlank()) ? ownerId : "default";

        List<ApiCallEntity> calls;
        if (documentId != null) {
            calls = apiCallRepository.findByDocumentIdOrderByCreatedAtDesc(documentId);
        } else {
            calls = apiCallRepository.findByOwnerIdOrderByCreatedAtDesc(effectiveOwner);
        }

        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalCharacters = BigDecimal.ZERO;
        BigDecimal totalPages = BigDecimal.ZERO;

        for (ApiCallEntity call : calls) {
            if (call.getEstCostInr() != null) {
                totalCost = totalCost.add(call.getEstCostInr());
            }
            if ("characters".equalsIgnoreCase(call.getUnitType()) && call.getUnits() != null) {
                totalCharacters = totalCharacters.add(call.getUnits());
            } else if ("pages".equalsIgnoreCase(call.getUnitType()) && call.getUnits() != null) {
                totalPages = totalPages.add(call.getUnits());
            }
        }

        // Endpoint breakdown grouping
        Map<String, List<ApiCallEntity>> byEndpoint = calls.stream()
                .collect(Collectors.groupingBy(ApiCallEntity::getEndpoint));

        List<EndpointUsageBreakdown> breakdowns = new ArrayList<>();
        for (Map.Entry<String, List<ApiCallEntity>> entry : byEndpoint.entrySet()) {
            String endpoint = entry.getKey();
            List<ApiCallEntity> endpointCalls = entry.getValue();

            long count = endpointCalls.size();
            BigDecimal units = endpointCalls.stream()
                    .map(ApiCallEntity::getUnits)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            String unitType = endpointCalls.getFirst().getUnitType();

            BigDecimal cost = endpointCalls.stream()
                    .map(ApiCallEntity::getEstCostInr)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            double avgLatency = endpointCalls.stream()
                    .mapToLong(ApiCallEntity::getLatencyMs)
                    .average()
                    .orElse(0.0);

            long minLatency = endpointCalls.stream()
                    .mapToLong(ApiCallEntity::getLatencyMs)
                    .min()
                    .orElse(0L);

            long maxLatency = endpointCalls.stream()
                    .mapToLong(ApiCallEntity::getLatencyMs)
                    .max()
                    .orElse(0L);

            breakdowns.add(new EndpointUsageBreakdown(
                    endpoint,
                    count,
                    units,
                    unitType,
                    cost.setScale(4, RoundingMode.HALF_UP),
                    Math.round(avgLatency * 100.0) / 100.0,
                    minLatency,
                    maxLatency
            ));
        }

        // Recent calls (top 20)
        List<ApiCallSummary> recentCalls = calls.stream()
                .limit(20)
                .map(c -> new ApiCallSummary(
                        c.getId(),
                        c.getDocumentId(),
                        c.getEndpoint(),
                        c.getUnits(),
                        c.getUnitType(),
                        c.getLatencyMs(),
                        c.getHttpStatus(),
                        c.getEstCostInr(),
                        c.getCreatedAt()
                ))
                .toList();

        int dailyWords = getDailyWordsProcessed(effectiveOwner);
        int remainingWords = Math.max(0, dailyWordCap - dailyWords);
        boolean capExceeded = dailyWords >= dailyWordCap;

        return new UsageSummaryResponse(
                effectiveOwner,
                documentId,
                dailyWords,
                dailyWordCap,
                remainingWords,
                capExceeded,
                totalCost.setScale(4, RoundingMode.HALF_UP),
                calls.size(),
                totalCharacters,
                totalPages,
                breakdowns,
                recentCalls
        );
    }
}
