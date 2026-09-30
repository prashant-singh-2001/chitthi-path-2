package com.chitthi.worker;

import com.chitthi.client.sarvam.SarvamClient;
import com.chitthi.client.sarvam.dto.DigitiseJobStatusResponse;
import com.chitthi.config.RabbitConfig;
import com.chitthi.config.SarvamProperties;
import com.chitthi.domain.entity.OcrBatchEntity;
import com.chitthi.domain.entity.PageEntity;
import com.chitthi.domain.entity.StageTaskEntity;
import com.chitthi.messaging.dto.PageTranslateMessage;
import com.chitthi.repository.DocumentRepository;
import com.chitthi.repository.OcrBatchRepository;
import com.chitthi.repository.PageRepository;
import com.chitthi.repository.StageTaskRepository;
import com.chitthi.service.DocumentProgressEventService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class OcrBatchStatusPoller {

    private static final Logger log = LoggerFactory.getLogger(OcrBatchStatusPoller.class);

    private final OcrBatchRepository ocrBatchRepository;
    private final PageRepository pageRepository;
    private final DocumentRepository documentRepository;
    private final StageTaskRepository stageTaskRepository;
    private final SarvamClient sarvamClient;
    private final SarvamProperties sarvamProperties;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final DocumentProgressEventService eventService;
    private final com.chitthi.service.UsageLedgerService usageLedgerService;

    public OcrBatchStatusPoller(OcrBatchRepository ocrBatchRepository,
                               PageRepository pageRepository,
                               DocumentRepository documentRepository,
                               StageTaskRepository stageTaskRepository,
                               SarvamClient sarvamClient,
                               SarvamProperties sarvamProperties,
                               RabbitTemplate rabbitTemplate,
                               ObjectMapper objectMapper,
                               DocumentProgressEventService eventService) {
        this(ocrBatchRepository, pageRepository, documentRepository, stageTaskRepository,
                sarvamClient, sarvamProperties, rabbitTemplate, objectMapper, eventService, null);
    }

    public OcrBatchStatusPoller(OcrBatchRepository ocrBatchRepository,
                               PageRepository pageRepository,
                               DocumentRepository documentRepository,
                               StageTaskRepository stageTaskRepository,
                               SarvamClient sarvamClient,
                               SarvamProperties sarvamProperties,
                               RabbitTemplate rabbitTemplate,
                               ObjectMapper objectMapper,
                               DocumentProgressEventService eventService,
                               com.chitthi.service.UsageLedgerService usageLedgerService) {
        this.ocrBatchRepository = ocrBatchRepository;
        this.pageRepository = pageRepository;
        this.documentRepository = documentRepository;
        this.stageTaskRepository = stageTaskRepository;
        this.sarvamClient = sarvamClient;
        this.sarvamProperties = sarvamProperties;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.eventService = eventService;
        this.usageLedgerService = usageLedgerService;
    }

    @Scheduled(fixedDelayString = "${sarvam.doc-ai-poll-interval-ms:5000}")
    public void pollActiveOcrBatches() {
        List<OcrBatchEntity> activeBatches = ocrBatchRepository
                .findByStatusInAndNextPollAtBefore(List.of("RUNNING"), Instant.now());

        if (activeBatches.isEmpty()) {
            return;
        }

        log.debug("Found {} active OCR batches ready for polling", activeBatches.size());

        for (OcrBatchEntity batch : activeBatches) {
            try {
                pollBatchStatus(batch);
            } catch (Exception e) {
                log.error("Error polling OCR batch {}", batch.getId(), e);
            }
        }
    }

    @Transactional
    public void pollBatchStatus(OcrBatchEntity batch) {
        if (batch.getSarvamJobId() == null || batch.getSarvamJobId().isBlank()) {
            return;
        }

        log.debug("Polling status for batchId: {}, jobId: {}", batch.getId(), batch.getSarvamJobId());
        DigitiseJobStatusResponse status = sarvamClient.getJobStatus(batch.getSarvamJobId());

        if (status.isCompleted() || status.isPartiallyCompleted()) {
            handleCompletedBatch(batch, status);
        } else if (status.isFailed()) {
            handleFailedBatch(batch, status.errorMessage());
        } else {
            // Still pending or running
            handlePendingOrRunningBatch(batch);
        }
    }

    private void handleCompletedBatch(OcrBatchEntity batch, DigitiseJobStatusResponse status) {
        log.info("OCR batch {} completed successfully by Sarvam. Downloading results from {}",
                batch.getId(), status.downloadUrl());

        int[] range = parsePageRange(batch.getPageRange());
        int startPage = range[0];
        int endPage = range[1];

        List<PageEntity> pages = pageRepository
                .findByDocumentIdAndPageNoBetweenOrderByPageNoAsc(batch.getDocument().getId(), startPage, endPage);

        Map<Integer, String> extractedPageTexts = new HashMap<>();

        if (status.downloadUrl() != null && !status.downloadUrl().isBlank()) {
            try {
                byte[] zipBytes = sarvamClient.downloadJobResult(status.downloadUrl());
                extractedPageTexts = extractPageTextsFromZip(zipBytes, startPage, endPage);
            } catch (Exception e) {
                log.error("Failed to download or parse OCR result ZIP for batch {}", batch.getId(), e);
            }
        }

        String ownerId = batch.getDocument().getOwnerId();

        for (PageEntity page : pages) {
            String text = extractedPageTexts.getOrDefault(page.getPageNo(), "");
            String textHash = calculateSha256(text);

            page.setOriginalText(text);
            page.setTextHash(textHash);
            page.setStatus("OCR_DONE");
            pageRepository.save(page);

            // Record stage task idempotency
            String idempotencyKey = page.getId() + ":OCR:" + textHash;
            StageTaskEntity stageTask = new StageTaskEntity(
                    UUID.randomUUID(),
                    page,
                    "OCR",
                    idempotencyKey,
                    "COMPLETED"
            );
            stageTaskRepository.save(stageTask);

            // FR15: Count words and verify daily word cap before dispatching downstream
            if (usageLedgerService != null) {
                int pageWords = usageLedgerService.countWords(text);
                usageLedgerService.recordWordsProcessed(pageWords);

                int dailyWords = usageLedgerService.getDailyWordsProcessed(ownerId);
                if (dailyWords > usageLedgerService.getDailyWordCap()) {
                    log.warn("Page {} of doc {} halted: owner '{}' exceeded daily word cap (current: {} words, cap: {})",
                            page.getPageNo(), batch.getDocument().getId(), ownerId, dailyWords, usageLedgerService.getDailyWordCap());
                    page.setStatus("CAPPED");
                    pageRepository.save(page);

                    batch.getDocument().setStatus("PARTIAL");
                    documentRepository.save(batch.getDocument());

                    eventService.emitProgress(batch.getDocument().getId(), page.getPageNo(), "OCR", "FAILED",
                            "Page " + page.getPageNo() + " paused: reached daily limit of 7,000 words");
                    continue;
                }
            }

            // Dispatch to translate queue
            PageTranslateMessage translateMessage = new PageTranslateMessage(
                    page.getId(),
                    batch.getDocument().getId(),
                    page.getPageNo(),
                    batch.getDocument().getLanguage(),
                    textHash
            );
            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE_NAME, RabbitConfig.TRANSLATE_ROUTING_KEY, translateMessage);
            log.info("Dispatched translate message for page {} of document {}", page.getPageNo(), batch.getDocument().getId());
            eventService.emitProgress(batch.getDocument().getId(), page.getPageNo(), "OCR", "COMPLETED", 
                    "Page " + page.getPageNo() + " OCR text extracted");
        }

        batch.setStatus("COMPLETED");
        ocrBatchRepository.save(batch);
    }

    private void handleFailedBatch(OcrBatchEntity batch, String errorMessage) {
        log.error("Sarvam Document AI job {} for batch {} failed with error: {}",
                batch.getSarvamJobId(), batch.getId(), errorMessage);

        batch.setStatus("FAILED");
        ocrBatchRepository.save(batch);

        eventService.emitProgress(batch.getDocument().getId(), null, "OCR", "FAILED", errorMessage);

        int[] range = parsePageRange(batch.getPageRange());
        List<PageEntity> pages = pageRepository
                .findByDocumentIdAndPageNoBetweenOrderByPageNoAsc(batch.getDocument().getId(), range[0], range[1]);
        for (PageEntity page : pages) {
            page.setStatus("FAILED");
            pageRepository.save(page);
        }
    }

    private void handlePendingOrRunningBatch(OcrBatchEntity batch) {
        int nextPollCount = batch.getPollCount() + 1;
        batch.setPollCount(nextPollCount);

        if (nextPollCount >= sarvamProperties.docAiMaxPollAttempts()) {
            log.warn("OCR batch {} exceeded maximum poll attempts ({}), marking as FAILED",
                    batch.getId(), sarvamProperties.docAiMaxPollAttempts());
            handleFailedBatch(batch, "Poll attempts exceeded max limit");
            return;
        }

        long backoffDelaySeconds = Math.min(5L * nextPollCount, 30L);
        batch.setNextPollAt(Instant.now().plusSeconds(backoffDelaySeconds));
        ocrBatchRepository.save(batch);
        log.debug("Batch {} still processing. Scheduled next poll in {} seconds", batch.getId(), backoffDelaySeconds);
    }

    public Map<Integer, String> extractPageTextsFromZip(byte[] zipBytes, int startPage, int endPage) {
        Map<Integer, String> pageTexts = new HashMap<>();
        Map<String, String> entries = new HashMap<>();

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    zis.transferTo(baos);
                    entries.put(entry.getName(), baos.toString(StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            log.error("Failed to read zip archive entries", e);
            return pageTexts;
        }

        for (int p = startPage; p <= endPage; p++) {
            int pageIndex = p - startPage + 1; // 1-based index within batch
            String pageText = findTextForPage(entries, p, pageIndex);
            if (pageText != null) {
                pageTexts.put(p, pageText);
            }
        }

        // If no per-page text was matched, check for single markdown document
        if (pageTexts.isEmpty()) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                if (entry.getKey().endsWith(".md")) {
                    String mdContent = entry.getValue();
                    if (startPage == endPage) {
                        pageTexts.put(startPage, mdContent);
                    } else {
                        // Split by markdown page headers if available, or distribute
                        String[] chunks = mdContent.split("(?i)(?=##\\s*page|<!--\\s*page)");
                        for (int i = 0; i < chunks.length && (startPage + i) <= endPage; i++) {
                            pageTexts.put(startPage + i, chunks[i].trim());
                        }
                    }
                    break;
                }
            }
        }

        return pageTexts;
    }

    private String findTextForPage(Map<String, String> entries, int globalPageNo, int batchPageIndex) {
        String[] possibleKeys = {
                "page_" + batchPageIndex + ".json",
                "page_" + String.format("%03d", batchPageIndex) + ".json",
                "metadata/page_" + batchPageIndex + ".json",
                "metadata/page_" + String.format("%03d", batchPageIndex) + ".json",
                "page_" + globalPageNo + ".json",
                "page_" + String.format("%03d", globalPageNo) + ".json",
                "metadata/page_" + globalPageNo + ".json",
                "page_" + batchPageIndex + ".md",
                "page_" + globalPageNo + ".md"
        };

        for (String key : possibleKeys) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                if (entry.getKey().toLowerCase().endsWith(key.toLowerCase())) {
                    String content = entry.getValue();
                    if (key.endsWith(".json")) {
                        return extractTextFromJson(content);
                    }
                    return content;
                }
            }
        }
        return null;
    }

    private String extractTextFromJson(String jsonContent) {
        try {
            JsonNode root = objectMapper.readTree(jsonContent);
            if (root.has("text")) {
                return root.get("text").asText();
            }
            if (root.has("content")) {
                return root.get("content").asText();
            }
            if (root.has("markdown")) {
                return root.get("markdown").asText();
            }
            return jsonContent;
        } catch (Exception e) {
            return jsonContent;
        }
    }

    private int[] parsePageRange(String pageRange) {
        try {
            String[] parts = pageRange.split("-");
            if (parts.length == 2) {
                return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
            }
        } catch (Exception ignored) {}
        return new int[]{1, 1};
    }

    private String calculateSha256(String text) {
        if (text == null) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
