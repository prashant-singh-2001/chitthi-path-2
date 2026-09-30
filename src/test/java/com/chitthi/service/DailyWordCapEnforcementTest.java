package com.chitthi.service;

import com.chitthi.api.dto.DocumentUploadRequest;
import com.chitthi.client.sarvam.SarvamClient;
import com.chitthi.client.sarvam.dto.DigitiseJobStatusResponse;
import com.chitthi.config.RabbitConfig;
import com.chitthi.config.SarvamProperties;
import com.chitthi.domain.entity.DocumentEntity;
import com.chitthi.domain.entity.OcrBatchEntity;
import com.chitthi.domain.entity.PageEntity;
import com.chitthi.exception.DailyWordCapExceededException;
import com.chitthi.repository.DocumentRepository;
import com.chitthi.repository.OcrBatchRepository;
import com.chitthi.repository.OutboxRepository;
import com.chitthi.repository.PageRepository;
import com.chitthi.repository.StageTaskRepository;
import com.chitthi.storage.ObjectStorageService;
import com.chitthi.worker.OcrBatchStatusPoller;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DailyWordCapEnforcementTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private PageRepository pageRepository;

    @Mock
    private OcrBatchRepository ocrBatchRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private ObjectStorageService objectStorageService;

    @Mock
    private PdfSplitterService pdfSplitterService;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private DocumentProgressEventService eventService;

    @Mock
    private UsageLedgerService usageLedgerService;

    @Mock
    private StageTaskRepository stageTaskRepository;

    @Mock
    private SarvamClient sarvamClient;

    @Mock
    private SarvamProperties sarvamProperties;

    @Test
    void ingestDocument_whenDailyCapExceeded_shouldRejectUploadWithException() {
        DocumentIngestionService ingestionService = new DocumentIngestionService(
                documentRepository,
                pageRepository,
                ocrBatchRepository,
                outboxRepository,
                objectStorageService,
                pdfSplitterService,
                rabbitTemplate,
                objectMapper,
                eventService,
                usageLedgerService
        );

        String ownerId = "capped-user";
        when(usageLedgerService.isDailyCapExceeded(ownerId)).thenReturn(true);
        when(usageLedgerService.getDailyWordsProcessed(ownerId)).thenReturn(7500);
        when(usageLedgerService.getDailyWordCap()).thenReturn(7000);

        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", new byte[]{1, 2, 3});
        DocumentUploadRequest request = new DocumentUploadRequest("Letter", "hi", ownerId, 1980, List.of());

        assertThatThrownBy(() -> ingestionService.ingestDocument(file, request))
                .isInstanceOf(DailyWordCapExceededException.class)
                .hasMessageContaining("Daily word processing cap reached")
                .hasMessageContaining("Cap: 7000");

        verifyNoInteractions(objectStorageService);
    }

    @Test
    void ocrBatchStatusPoller_whenPageExceedsCap_shouldMarkPageCappedAndPauseTranslate() throws Exception {
        OcrBatchStatusPoller poller = new OcrBatchStatusPoller(
                ocrBatchRepository,
                pageRepository,
                documentRepository,
                stageTaskRepository,
                sarvamClient,
                sarvamProperties,
                rabbitTemplate,
                objectMapper,
                eventService,
                usageLedgerService
        );

        UUID docId = UUID.randomUUID();
        DocumentEntity doc = new DocumentEntity(docId, "capped-user", "Letter", "hi", "PROCESSING", "[]", 1980);
        OcrBatchEntity batch = new OcrBatchEntity(UUID.randomUUID(), doc, "1-1", "job-123", "RUNNING");

        UUID pageId = UUID.randomUUID();
        PageEntity page = new PageEntity(pageId, doc, 1, "page_1.png", "PENDING");

        when(ocrBatchRepository.findByStatusInAndNextPollAtBefore(anyList(), any(Instant.class)))
                .thenReturn(List.of(batch));
        when(sarvamClient.getJobStatus("job-123"))
                .thenReturn(new DigitiseJobStatusResponse("job-123", "completed", "http://download.url", null));

        // Create sample ZIP with page 1 JSON
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("metadata/page_001.json"));
            zos.write("{\"text\": \"बहुत सारे शब्द जो सात हज़ार की सीमा पार कर जाते हैं\"}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        when(sarvamClient.downloadJobResult("http://download.url")).thenReturn(baos.toByteArray());
        when(pageRepository.findByDocumentIdAndPageNoBetweenOrderByPageNoAsc(docId, 1, 1))
                .thenReturn(List.of(page));

        // Simulate cap exceeded
        when(usageLedgerService.countWords(anyString())).thenReturn(500);
        when(usageLedgerService.getDailyWordsProcessed("capped-user")).thenReturn(7200);
        when(usageLedgerService.getDailyWordCap()).thenReturn(7000);

        poller.pollActiveOcrBatches();

        // 1. Verify page status is marked CAPPED
        assertThat(page.getStatus()).isEqualTo("CAPPED");
        verify(pageRepository, atLeastOnce()).save(page);

        // 2. Verify document status set to PARTIAL
        assertThat(doc.getStatus()).isEqualTo("PARTIAL");
        verify(documentRepository).save(doc);

        // 3. Verify message was NEVER published to translate.queue
        verify(rabbitTemplate, never()).convertAndSend(
                eq(RabbitConfig.EXCHANGE_NAME),
                eq(RabbitConfig.TRANSLATE_ROUTING_KEY),
                any(Object.class)
        );

        // 4. Verify SSE warning emitted
        verify(eventService).emitProgress(eq(docId), eq(1), eq("OCR"), eq("FAILED"), contains("paused: reached daily limit"));
    }
}
