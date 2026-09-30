package com.chitthi.service;

import com.chitthi.api.dto.UsageSummaryResponse;
import com.chitthi.domain.entity.ApiCallEntity;
import com.chitthi.domain.entity.DocumentEntity;
import com.chitthi.domain.entity.PageEntity;
import com.chitthi.repository.ApiCallRepository;
import com.chitthi.repository.PageRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UsageLedgerServiceTest {

    @Mock
    private ApiCallRepository apiCallRepository;

    @Mock
    private PageRepository pageRepository;

    private SimpleMeterRegistry meterRegistry;
    private UsageLedgerService usageLedgerService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        usageLedgerService = new UsageLedgerService(
                apiCallRepository,
                pageRepository,
                7000,
                meterRegistry
        );
    }

    @Test
    void countWords_shouldAccuratelyCountDevanagariAndPunctuationSeparatedWords() {
        assertThat(usageLedgerService.countWords(null)).isEqualTo(0);
        assertThat(usageLedgerService.countWords("   ")).isEqualTo(0);

        // Devanagari with danda (।) and double danda (॥)
        String hindiText = "नानाजी का पत्र मिला। सब कुशल मंगल है॥";
        assertThat(usageLedgerService.countWords(hindiText)).isEqualTo(8);

        // Mixed with english punctuation
        String mixedText = "गांधीजी ने कहा, 'सत्य ही ईश्वर है!'";
        assertThat(usageLedgerService.countWords(mixedText)).isEqualTo(7);
    }

    @Test
    void getDailyWordsProcessed_shouldSumWordsAcrossOwnerPagesToday() {
        String ownerId = "archivist-1";
        DocumentEntity doc = new DocumentEntity(UUID.randomUUID(), ownerId, "Letter", "hi", "COMPLETE", "[]", 1974);

        PageEntity p1 = new PageEntity(UUID.randomUUID(), doc, 1, "k1", "INDEXED");
        p1.setOriginalText("पहला पृष्ठ जिसमें पाँच शब्द हैं"); // 6 words
        PageEntity p2 = new PageEntity(UUID.randomUUID(), doc, 2, "k2", "INDEXED");
        p2.setOriginalText("दूसरा पृष्ठ जिसमें भी कुछ शब्द हैं"); // 7 words

        when(pageRepository.findPagesByOwnerSince(eq(ownerId), any(Instant.class)))
                .thenReturn(List.of(p1, p2));

        int words = usageLedgerService.getDailyWordsProcessed(ownerId);
        assertThat(words).isEqualTo(13);
    }

    @Test
    void isDailyCapExceeded_whenUnderCap_shouldReturnFalse() {
        when(pageRepository.findPagesByOwnerSince(eq("user1"), any(Instant.class)))
                .thenReturn(List.of());

        assertThat(usageLedgerService.isDailyCapExceeded("user1")).isFalse();
    }

    @Test
    void isDailyCapExceeded_whenAtOrOverCap_shouldReturnTrue() {
        DocumentEntity doc = new DocumentEntity(UUID.randomUUID(), "heavy-user", "Book", "hi", "COMPLETE", "[]", 1950);
        PageEntity p = new PageEntity(UUID.randomUUID(), doc, 1, "k", "INDEXED");

        // Generate 7,001 words
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7001; i++) {
            sb.append("शब्द ");
        }
        p.setOriginalText(sb.toString());

        when(pageRepository.findPagesByOwnerSince(eq("heavy-user"), any(Instant.class)))
                .thenReturn(List.of(p));

        assertThat(usageLedgerService.isDailyCapExceeded("heavy-user")).isTrue();
    }

    @Test
    void recordApiCall_shouldPersistEntityAndIncrementMetrics() {
        UUID docId = UUID.randomUUID();
        when(apiCallRepository.save(any(ApiCallEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiCallEntity saved = usageLedgerService.recordApiCall(
                docId,
                "/translate",
                BigDecimal.valueOf(1500),
                "characters",
                320L,
                200,
                new BigDecimal("1.5000")
        );

        assertThat(saved.getEndpoint()).isEqualTo("/translate");
        assertThat(saved.getEstCostInr()).isEqualTo(new BigDecimal("1.5000"));
        verify(apiCallRepository).save(any(ApiCallEntity.class));

        // Verify Micrometer counter updated
        double totalSpend = meterRegistry.get("chitthi.api.spend.total").counter().count();
        assertThat(totalSpend).isEqualTo(1.5);
    }

    @Test
    void getUsageSummary_shouldAggregateEndpointStatsAndDailyCap() {
        String ownerId = "archivist-1";
        UUID docId = UUID.randomUUID();

        ApiCallEntity call1 = new ApiCallEntity(
                UUID.randomUUID(), docId, "/translate", BigDecimal.valueOf(1000), "characters", 200L, 200, new BigDecimal("1.0000")
        );
        ApiCallEntity call2 = new ApiCallEntity(
                UUID.randomUUID(), docId, "/translate", BigDecimal.valueOf(1500), "characters", 400L, 200, new BigDecimal("1.5000")
        );
        ApiCallEntity call3 = new ApiCallEntity(
                UUID.randomUUID(), docId, "/text-to-speech", BigDecimal.valueOf(2000), "characters", 600L, 200, new BigDecimal("6.0000")
        );

        when(apiCallRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId))
                .thenReturn(List.of(call1, call2, call3));
        when(pageRepository.findPagesByOwnerSince(eq(ownerId), any(Instant.class)))
                .thenReturn(List.of());

        UsageSummaryResponse summary = usageLedgerService.getUsageSummary(ownerId, null);

        assertThat(summary.ownerId()).isEqualTo(ownerId);
        assertThat(summary.totalEstimatedCostInr()).isEqualByComparingTo("8.5000");
        assertThat(summary.totalCallsCount()).isEqualTo(3);
        assertThat(summary.totalCharactersProcessed()).isEqualByComparingTo("4500");
        assertThat(summary.dailyWordCap()).isEqualTo(7000);
        assertThat(summary.dailyWordsProcessed()).isEqualTo(0);
        assertThat(summary.dailyWordsRemaining()).isEqualTo(7000);
        assertThat(summary.capExceeded()).isFalse();

        // Breakdown check
        assertThat(summary.endpointBreakdown()).hasSize(2);
        var translateBreakdown = summary.endpointBreakdown().stream()
                .filter(b -> b.endpoint().equals("/translate"))
                .findFirst().orElseThrow();
        assertThat(translateBreakdown.callCount()).isEqualTo(2);
        assertThat(translateBreakdown.avgLatencyMs()).isEqualTo(300.0);
        assertThat(translateBreakdown.minLatencyMs()).isEqualTo(200L);
        assertThat(translateBreakdown.maxLatencyMs()).isEqualTo(400L);
    }
}
