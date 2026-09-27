package com.chitthi.worker;

import com.chitthi.client.sarvam.SarvamClient;
import com.chitthi.client.sarvam.dto.DigitiseJobResponse;
import com.chitthi.config.RabbitConfig;
import com.chitthi.domain.entity.ApiCallEntity;
import com.chitthi.domain.entity.OcrBatchEntity;
import com.chitthi.messaging.dto.OcrBatchMessage;
import com.chitthi.repository.ApiCallRepository;
import com.chitthi.repository.OcrBatchRepository;
import com.chitthi.storage.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class OcrWorker {

    private static final Logger log = LoggerFactory.getLogger(OcrWorker.class);
    private static final BigDecimal COST_PER_PAGE_INR = new BigDecimal("0.5000");

    private final OcrBatchRepository ocrBatchRepository;
    private final ApiCallRepository apiCallRepository;
    private final ObjectStorageService objectStorageService;
    private final SarvamClient sarvamClient;

    public OcrWorker(OcrBatchRepository ocrBatchRepository,
                     ApiCallRepository apiCallRepository,
                     ObjectStorageService objectStorageService,
                     SarvamClient sarvamClient) {
        this.ocrBatchRepository = ocrBatchRepository;
        this.apiCallRepository = apiCallRepository;
        this.objectStorageService = objectStorageService;
        this.sarvamClient = sarvamClient;
    }

    @RabbitListener(queues = RabbitConfig.OCR_QUEUE)
    @Transactional
    public void processOcrBatch(OcrBatchMessage message) {
        log.info("Received OCR batch task for batchId: {}, docId: {}, pageRange: {}",
                message.batchId(), message.documentId(), message.pageRange());

        Optional<OcrBatchEntity> batchOpt = ocrBatchRepository.findById(message.batchId());
        if (batchOpt.isEmpty()) {
            log.warn("OcrBatch {} not found in database, skipping", message.batchId());
            return;
        }

        OcrBatchEntity batch = batchOpt.get();

        // Idempotency check: if already submitted or completed, do not make another paid API call
        if ("RUNNING".equalsIgnoreCase(batch.getStatus()) || "COMPLETED".equalsIgnoreCase(batch.getStatus())) {
            log.info("OcrBatch {} is already in status {}, skipping duplicate submission", batch.getId(), batch.getStatus());
            return;
        }
        if (batch.getSarvamJobId() != null && !batch.getSarvamJobId().isBlank()) {
            log.info("OcrBatch {} already has Sarvam job ID {}, skipping duplicate submission", batch.getId(), batch.getSarvamJobId());
            return;
        }

        try {
            // 1. Download batch file from MinIO
            byte[] fileBytes = objectStorageService.downloadFile(message.storageKey());
            String filename = extractFilename(message.storageKey());

            // 2. Submit async digitize job to Sarvam AI
            long startTime = System.currentTimeMillis();
            DigitiseJobResponse response = sarvamClient.submitDigitiseJob(fileBytes, filename, message.languageCode());
            long latencyMs = System.currentTimeMillis() - startTime;

            log.info("Sarvam Document AI job submitted successfully: jobId = {} for batch = {}",
                    response.jobId(), batch.getId());

            // 3. Update OcrBatch state
            batch.setSarvamJobId(response.jobId());
            batch.setStatus("RUNNING");
            batch.setPollCount(0);
            batch.setNextPollAt(Instant.now().plusSeconds(5));
            ocrBatchRepository.save(batch);

            // 4. Record ledger entry in api_call table
            int pageCount = calculatePageCount(message.pageRange());
            BigDecimal estCost = COST_PER_PAGE_INR.multiply(BigDecimal.valueOf(pageCount));
            ApiCallEntity apiCall = new ApiCallEntity(
                    UUID.randomUUID(),
                    message.documentId(),
                    "/doc-ai/v1/job/digitise",
                    BigDecimal.valueOf(pageCount),
                    "pages",
                    latencyMs,
                    200,
                    estCost
            );
            apiCallRepository.save(apiCall);

        } catch (Exception e) {
            log.error("Failed to submit Sarvam Document AI job for batch: {}", batch.getId(), e);
            batch.setStatus("FAILED");
            ocrBatchRepository.save(batch);
            throw new RuntimeException("Error processing OCR batch " + batch.getId(), e);
        }
    }

    private String extractFilename(String storageKey) {
        int idx = storageKey.lastIndexOf('/');
        return (idx >= 0) ? storageKey.substring(idx + 1) : storageKey;
    }

    private int calculatePageCount(String pageRange) {
        try {
            String[] parts = pageRange.split("-");
            if (parts.length == 2) {
                int start = Integer.parseInt(parts[0].trim());
                int end = Integer.parseInt(parts[1].trim());
                return Math.max(1, end - start + 1);
            }
        } catch (Exception ignored) {}
        return 1;
    }
}
