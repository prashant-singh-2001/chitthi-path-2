package com.chitthi.worker;

import com.chitthi.config.RabbitConfig;
import com.chitthi.domain.entity.DocumentEntity;
import com.chitthi.domain.entity.OcrBatchEntity;
import com.chitthi.domain.entity.PageEntity;
import com.chitthi.domain.entity.StageTaskEntity;
import com.chitthi.repository.DocumentRepository;
import com.chitthi.repository.OcrBatchRepository;
import com.chitthi.repository.PageRepository;
import com.chitthi.repository.StageTaskRepository;
import com.chitthi.service.DocumentProgressEventService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class DeadLetterWorker {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterWorker.class);

    private final PageRepository pageRepository;
    private final DocumentRepository documentRepository;
    private final OcrBatchRepository ocrBatchRepository;
    private final StageTaskRepository stageTaskRepository;
    private final DocumentProgressEventService eventService;
    private final ObjectMapper objectMapper;

    public DeadLetterWorker(PageRepository pageRepository,
                            DocumentRepository documentRepository,
                            OcrBatchRepository ocrBatchRepository,
                            StageTaskRepository stageTaskRepository,
                            DocumentProgressEventService eventService,
                            ObjectMapper objectMapper) {
        this.pageRepository = pageRepository;
        this.documentRepository = documentRepository;
        this.ocrBatchRepository = ocrBatchRepository;
        this.stageTaskRepository = stageTaskRepository;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.DEAD_LETTER_QUEUE)
    @Transactional
    public void processDeadLetter(Message message) {
        Map<String, Object> headers = message.getMessageProperties().getHeaders();
        String originalQueue = (String) headers.getOrDefault("x-original-queue", "unknown");
        String exceptionMsg = (String) headers.getOrDefault("x-exception-message", "Unknown failure after retries exhausted");

        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        log.warn("Received dead-letter message from queue: {}. Error: {}. Payload: {}", originalQueue, exceptionMsg, payload);

        try {
            JsonNode json = objectMapper.readTree(payload);

            if (json.has("pageId")) {
                UUID pageId = UUID.fromString(json.get("pageId").asText());
                UUID docId = json.has("documentId") ? UUID.fromString(json.get("documentId").asText()) : null;
                handlePageFailure(pageId, docId, originalQueue, exceptionMsg);
            } else if (json.has("batchId")) {
                UUID batchId = UUID.fromString(json.get("batchId").asText());
                UUID docId = json.has("documentId") ? UUID.fromString(json.get("documentId").asText()) : null;
                handleOcrBatchFailure(batchId, docId, exceptionMsg);
            } else if (json.has("documentId")) {
                UUID docId = UUID.fromString(json.get("documentId").asText());
                handleDocumentFailure(docId, exceptionMsg);
            }

        } catch (Exception e) {
            log.error("Failed to process dead-letter payload: {}", payload, e);
        }
    }

    private void handlePageFailure(UUID pageId, UUID docId, String originalQueue, String error) {
        Optional<PageEntity> pageOpt = pageRepository.findById(pageId);
        if (pageOpt.isEmpty()) {
            return;
        }

        PageEntity page = pageOpt.get();
        page.setStatus("FAILED");
        pageRepository.save(page);

        // Update document status to PARTIAL
        UUID effectiveDocId = docId != null ? docId : page.getDocument().getId();
        documentRepository.findById(effectiveDocId).ifPresent(doc -> {
            doc.setStatus("PARTIAL");
            documentRepository.save(doc);
        });

        // Update stage task if found
        String stage = originalQueue.contains("translate") ? "TRANSLATE" : "TTS";
        List<StageTaskEntity> tasks = stageTaskRepository.findByPageIdAndStage(pageId, stage);
        for (StageTaskEntity task : tasks) {
            task.setStatus("DEAD_LETTERED");
            task.setLastError(error);
            stageTaskRepository.save(task);
        }

        eventService.emitProgress(effectiveDocId, page.getPageNo(), stage, "FAILED", 
                "Page " + page.getPageNo() + " exhausted retries and moved to dead-letter queue: " + error);
    }

    private void handleOcrBatchFailure(UUID batchId, UUID docId, String error) {
        Optional<OcrBatchEntity> batchOpt = ocrBatchRepository.findById(batchId);
        if (batchOpt.isEmpty()) {
            return;
        }

        OcrBatchEntity batch = batchOpt.get();
        batch.setStatus("FAILED");
        ocrBatchRepository.save(batch);

        UUID effectiveDocId = docId != null ? docId : batch.getDocument().getId();
        documentRepository.findById(effectiveDocId).ifPresent(doc -> {
            doc.setStatus("PARTIAL");
            documentRepository.save(doc);
        });

        eventService.emitProgress(effectiveDocId, null, "OCR", "FAILED", 
                "OCR Batch " + batch.getPageRange() + " exhausted retries: " + error);
    }

    private void handleDocumentFailure(UUID docId, String error) {
        documentRepository.findById(docId).ifPresent(doc -> {
            doc.setStatus("FAILED");
            documentRepository.save(doc);
            eventService.emitProgress(docId, null, "ASSEMBLE", "FAILED", 
                    "Document assembly exhausted retries: " + error);
        });
    }
}
