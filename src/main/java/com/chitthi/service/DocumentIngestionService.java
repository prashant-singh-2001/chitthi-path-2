package com.chitthi.service;

import com.chitthi.api.dto.*;
import com.chitthi.config.RabbitConfig;
import com.chitthi.domain.entity.DocumentEntity;
import com.chitthi.domain.entity.OcrBatchEntity;
import com.chitthi.domain.entity.OutboxEntity;
import com.chitthi.domain.entity.PageEntity;
import com.chitthi.messaging.dto.OcrBatchMessage;
import com.chitthi.exception.DocumentNotFoundException;
import com.chitthi.exception.UnauthorizedDocumentAccessException;
import com.chitthi.repository.DocumentRepository;
import com.chitthi.repository.OcrBatchRepository;
import com.chitthi.repository.OutboxRepository;
import com.chitthi.repository.PageRepository;
import com.chitthi.repository.StageTaskRepository;
import com.chitthi.storage.ObjectStorageService;
import com.chitthi.util.IdempotencyUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);
    private static final long MAX_FILE_SIZE = 20 * 1024 * 1024; // 20 MB

    private final DocumentRepository documentRepository;
    private final PageRepository pageRepository;
    private final OcrBatchRepository ocrBatchRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectStorageService objectStorageService;
    private final PdfSplitterService pdfSplitterService;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final DocumentProgressEventService eventService;
    private final UsageLedgerService usageLedgerService;
    private final StageTaskRepository stageTaskRepository;

    public DocumentIngestionService(DocumentRepository documentRepository,
                                  PageRepository pageRepository,
                                  OcrBatchRepository ocrBatchRepository,
                                  OutboxRepository outboxRepository,
                                  ObjectStorageService objectStorageService,
                                  PdfSplitterService pdfSplitterService,
                                  RabbitTemplate rabbitTemplate,
                                  ObjectMapper objectMapper,
                                  DocumentProgressEventService eventService) {
        this(documentRepository, pageRepository, ocrBatchRepository, outboxRepository,
                objectStorageService, pdfSplitterService, rabbitTemplate, objectMapper, eventService, null, null);
    }

    public DocumentIngestionService(DocumentRepository documentRepository,
                                  PageRepository pageRepository,
                                  OcrBatchRepository ocrBatchRepository,
                                  OutboxRepository outboxRepository,
                                  ObjectStorageService objectStorageService,
                                  PdfSplitterService pdfSplitterService,
                                  RabbitTemplate rabbitTemplate,
                                  ObjectMapper objectMapper,
                                  DocumentProgressEventService eventService,
                                  UsageLedgerService usageLedgerService) {
        this(documentRepository, pageRepository, ocrBatchRepository, outboxRepository,
                objectStorageService, pdfSplitterService, rabbitTemplate, objectMapper, eventService, usageLedgerService, null);
    }

    public DocumentIngestionService(DocumentRepository documentRepository,
                                  PageRepository pageRepository,
                                  OcrBatchRepository ocrBatchRepository,
                                  OutboxRepository outboxRepository,
                                  ObjectStorageService objectStorageService,
                                  PdfSplitterService pdfSplitterService,
                                  RabbitTemplate rabbitTemplate,
                                  ObjectMapper objectMapper,
                                  DocumentProgressEventService eventService,
                                  UsageLedgerService usageLedgerService,
                                  StageTaskRepository stageTaskRepository) {
        this.documentRepository = documentRepository;
        this.pageRepository = pageRepository;
        this.ocrBatchRepository = ocrBatchRepository;
        this.outboxRepository = outboxRepository;
        this.objectStorageService = objectStorageService;
        this.pdfSplitterService = pdfSplitterService;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.eventService = eventService;
        this.usageLedgerService = usageLedgerService;
        this.stageTaskRepository = stageTaskRepository;
    }

    @Transactional
    public DocumentUploadResponse ingestDocument(MultipartFile file, DocumentUploadRequest request) throws IOException {
        validateUploadFile(file);

        String ownerId = (request.ownerId() != null && !request.ownerId().isBlank())
                ? request.ownerId() : "default-user";

        // FR15: Reject new uploads if the owner has already exceeded their 7,000-word daily processing ceiling
        if (usageLedgerService != null && usageLedgerService.isDailyCapExceeded(ownerId)) {
            int currentWords = usageLedgerService.getDailyWordsProcessed(ownerId);
            throw new com.chitthi.exception.DailyWordCapExceededException(ownerId, currentWords, usageLedgerService.getDailyWordCap());
        }

        UUID documentId = UUID.randomUUID();

        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document";
        String extension = getFileExtension(originalFilename);
        boolean isPdf = extension.equalsIgnoreCase(".pdf");

        // 1. Upload raw original file
        String rawStorageKey = "documents/" + documentId + "/original" + extension;
        byte[] fileBytes = file.getBytes();
        objectStorageService.uploadFile(rawStorageKey, fileBytes, file.getContentType() != null ? file.getContentType() : "application/octet-stream");

        // 2. Prepare Document entity
        String tagsJson = serializeTags(request.tags());
        DocumentEntity document = new DocumentEntity(
                documentId,
                ownerId,
                request.title(),
                request.language(),
                "PROCESSING",
                tagsJson,
                request.year()
        );
        document = documentRepository.save(document);

        List<PageEntity> pagesToSave = new ArrayList<>();
        List<OcrBatchEntity> batchesToSave = new ArrayList<>();
        Map<UUID, String> batchStorageKeys = new HashMap<>();

        if (isPdf) {
            PdfSplitterService.SplitResult splitResult = pdfSplitterService.splitPdf(fileBytes);

            // Upload rendered page images
            for (PdfSplitterService.PageImageData pageImage : splitResult.pages()) {
                String pageImageKey = "documents/" + documentId + "/pages/page_" + pageImage.pageNo() + ".png";
                objectStorageService.uploadFile(pageImageKey, pageImage.imageBytes(), pageImage.contentType());

                PageEntity pageEntity = new PageEntity(
                        UUID.randomUUID(),
                        document,
                        pageImage.pageNo(),
                        pageImageKey,
                        "PENDING"
                );
                pagesToSave.add(pageEntity);
            }

            // Upload batch sub-PDFs
            for (PdfSplitterService.PdfBatchData batch : splitResult.batches()) {
                UUID batchId = UUID.randomUUID();
                String batchKey = "documents/" + documentId + "/batches/batch_" + batch.pageRange() + ".pdf";
                objectStorageService.uploadFile(batchKey, batch.pdfBytes(), "application/pdf");

                OcrBatchEntity batchEntity = new OcrBatchEntity(
                        batchId,
                        document,
                        batch.pageRange(),
                        null,
                        "PENDING"
                );
                batchesToSave.add(batchEntity);
                batchStorageKeys.put(batchId, batchKey);
            }
        } else {
            // Single image document
            String pageImageKey = "documents/" + documentId + "/pages/page_1" + extension;
            objectStorageService.uploadFile(pageImageKey, fileBytes, file.getContentType());

            PageEntity pageEntity = new PageEntity(
                    UUID.randomUUID(),
                    document,
                    1,
                    pageImageKey,
                    "PENDING"
            );
            pagesToSave.add(pageEntity);

            UUID batchId = UUID.randomUUID();
            OcrBatchEntity batchEntity = new OcrBatchEntity(
                    batchId,
                    document,
                    "1-1",
                    null,
                    "PENDING"
            );
            batchesToSave.add(batchEntity);
            batchStorageKeys.put(batchId, pageImageKey);
        }

        pageRepository.saveAll(pagesToSave);
        ocrBatchRepository.saveAll(batchesToSave);

        // 3. Dispatch OCR messages and record outbox events
        for (OcrBatchEntity batch : batchesToSave) {
            String storageKey = batchStorageKeys.get(batch.getId());
            OcrBatchMessage message = new OcrBatchMessage(
                    batch.getId(),
                    documentId,
                    batch.getPageRange(),
                    storageKey,
                    request.language()
            );

            OutboxEntity outbox = null;
            try {
                String payload = objectMapper.writeValueAsString(message);
                outbox = new OutboxEntity(UUID.randomUUID(), RabbitConfig.OCR_ROUTING_KEY, payload);
                outboxRepository.save(outbox);
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize outbox message for batch: {}", batch.getId(), e);
            }

            try {
                rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE_NAME, RabbitConfig.OCR_ROUTING_KEY, message);
                if (outbox != null) {
                    outbox.setPublishedAt(Instant.now());
                    outboxRepository.save(outbox);
                }
                log.info("Dispatched OCR batch message for batchId: {}, pageRange: {}", batch.getId(), batch.getPageRange());
            } catch (Exception e) {
                log.warn("RabbitMQ immediate dispatch failed for batchId: {}. Outbox poller will retry. Error: {}", batch.getId(), e.getMessage());
            }
        }

        eventService.emitProgress(documentId, null, "UPLOAD", "COMPLETED", 
                "Document uploaded successfully with " + pagesToSave.size() + " pages and " + batchesToSave.size() + " OCR batches");

        return new DocumentUploadResponse(
                documentId,
                document.getTitle(),
                document.getLanguage(),
                document.getStatus(),
                pagesToSave.size(),
                batchesToSave.size(),
                document.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public Optional<DocumentDetailResponse> getDocument(UUID documentId) {
        return documentRepository.findById(documentId).map(doc -> {
            List<PageEntity> pages = pageRepository.findByDocumentIdOrderByPageNoAsc(documentId);
            List<PageSummaryResponse> pageSummaries = pages.stream().map(p -> {
                String imageUrl = objectStorageService.generatePresignedUrl(p.getImageKey());
                return new PageSummaryResponse(
                        p.getId(),
                        p.getPageNo(),
                        p.getStatus(),
                        Boolean.TRUE.equals(p.getEdited()),
                        p.getOriginalText(),
                        p.getTranslatedText(),
                        imageUrl
                );
            }).toList();

            List<String> tags = parseTags(doc.getTags());

            return new DocumentDetailResponse(
                    doc.getId(),
                    doc.getOwnerId(),
                    doc.getTitle(),
                    doc.getLanguage(),
                    doc.getStatus(),
                    doc.getYear(),
                    tags,
                    pageSummaries,
                    doc.getCreatedAt(),
                    doc.getUpdatedAt()
            );
        });
    }

    @Transactional
    public Optional<DocumentDetailResponse> retryFailedStages(UUID documentId) {
        Optional<DocumentEntity> docOpt = documentRepository.findById(documentId);
        if (docOpt.isEmpty()) {
            return Optional.empty();
        }
        DocumentEntity doc = docOpt.get();

        // 1. Retry any failed OCR batches
        List<OcrBatchEntity> batches = ocrBatchRepository.findByDocumentId(documentId);
        for (OcrBatchEntity batch : batches) {
            if ("FAILED".equalsIgnoreCase(batch.getStatus())) {
                batch.setStatus("PENDING");
                batch.setSarvamJobId(null);
                batch.setPollCount(0);
                batch.setNextPollAt(null);
                ocrBatchRepository.save(batch);

                String storageKey = "documents/" + documentId + "/batches/batch_" + batch.getPageRange() + ".pdf";
                OcrBatchMessage msg = new OcrBatchMessage(
                        batch.getId(), documentId, batch.getPageRange(), storageKey, doc.getLanguage()
                );
                rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE_NAME, RabbitConfig.OCR_ROUTING_KEY, msg);
                log.info("Re-dispatched failed OCR batch {} for document {}", batch.getId(), documentId);
            }
        }

        // 2. Retry any failed pages
        List<PageEntity> pages = pageRepository.findByDocumentIdOrderByPageNoAsc(documentId);
        for (PageEntity page : pages) {
            if ("FAILED".equalsIgnoreCase(page.getStatus())) {
                if (page.getOriginalText() != null && !page.getOriginalText().isBlank()) {
                    if (page.getTranslatedText() == null || page.getTranslatedText().isBlank()) {
                        // Failed at Translate
                        page.setStatus("OCR_DONE");
                        pageRepository.save(page);
                        com.chitthi.messaging.dto.PageTranslateMessage msg = new com.chitthi.messaging.dto.PageTranslateMessage(
                                page.getId(), documentId, page.getPageNo(), doc.getLanguage(), page.getTextHash()
                        );
                        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE_NAME, RabbitConfig.TRANSLATE_ROUTING_KEY, msg);
                        log.info("Re-dispatched failed translation for page {} of doc {}", page.getPageNo(), documentId);
                    } else {
                        // Failed at TTS
                        page.setStatus("TRANSLATED");
                        pageRepository.save(page);
                        com.chitthi.messaging.dto.PageTtsMessage msg = new com.chitthi.messaging.dto.PageTtsMessage(
                                page.getId(), documentId, page.getPageNo(), doc.getLanguage(), page.getTextHash()
                        );
                        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE_NAME, RabbitConfig.TTS_ROUTING_KEY, msg);
                        log.info("Re-dispatched failed TTS for page {} of doc {}", page.getPageNo(), documentId);
                    }
                } else {
                    // Failed at OCR, will be picked up when OCR batch runs
                    page.setStatus("PENDING");
                    pageRepository.save(page);
                }
            }
        }

        doc.setStatus("PROCESSING");
        documentRepository.save(doc);

        eventService.emitProgress(documentId, null, "RETRY", "RUNNING", "Manual retry initiated for failed stages");

        return getDocument(documentId);
    }

    @Transactional
    public Optional<DocumentDetailResponse> editPageText(UUID documentId, int pageNo, String newText) {
        if (newText == null || newText.isBlank()) {
            throw new IllegalArgumentException("Edited text cannot be empty or blank");
        }

        Optional<DocumentEntity> docOpt = documentRepository.findById(documentId);
        if (docOpt.isEmpty()) {
            return Optional.empty();
        }
        DocumentEntity doc = docOpt.get();

        Optional<PageEntity> pageOpt = pageRepository.findByDocumentIdAndPageNo(documentId, pageNo);
        if (pageOpt.isEmpty()) {
            return Optional.empty();
        }
        PageEntity page = pageOpt.get();

        String trimmedText = newText.trim();
        String newHash = com.chitthi.util.IdempotencyUtils.sha256Hex(trimmedText);

        page.setOriginalText(trimmedText);
        page.setTextHash(newHash);
        page.setEdited(true);
        page.setStatus("OCR_DONE");
        page.setTranslatedText(null);
        pageRepository.save(page);

        doc.setStatus("PROCESSING");
        documentRepository.save(doc);

        com.chitthi.messaging.dto.PageTranslateMessage translateMessage = new com.chitthi.messaging.dto.PageTranslateMessage(
                page.getId(),
                documentId,
                pageNo,
                doc.getLanguage(),
                newHash
        );

        OutboxEntity outbox = null;
        try {
            String payload = objectMapper.writeValueAsString(translateMessage);
            outbox = new OutboxEntity(UUID.randomUUID(), RabbitConfig.TRANSLATE_ROUTING_KEY, payload);
            outboxRepository.save(outbox);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox message for edited page: {}", page.getId(), e);
        }

        try {
            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE_NAME, RabbitConfig.TRANSLATE_ROUTING_KEY, translateMessage);
            if (outbox != null) {
                outbox.setPublishedAt(Instant.now());
                outboxRepository.save(outbox);
            }
            log.info("Dispatched translate message for edited page {} of doc {}", pageNo, documentId);
        } catch (Exception e) {
            log.warn("Immediate dispatch failed for edited page {}. Outbox poller will retry: {}", pageNo, e.getMessage());
        }

        eventService.emitProgress(documentId, pageNo, "EDIT", "COMPLETED",
                "Archivist edited text for page " + pageNo + "; translation and audio regeneration initiated");

        return getDocument(documentId);
    }

    @Transactional(readOnly = true)
    public List<DocumentDetailResponse> listDocuments(String ownerId) {
        String effectiveOwner = (ownerId != null && !ownerId.isBlank()) ? ownerId : "default";
        return documentRepository.findByOwnerIdOrderByCreatedAtDesc(effectiveOwner)
                .stream()
                .map(doc -> {
                    List<PageEntity> pages = pageRepository.findByDocumentIdOrderByPageNoAsc(doc.getId());
                    List<PageSummaryResponse> pageSummaries = pages.stream().map(p -> new PageSummaryResponse(
                            p.getId(),
                            p.getPageNo(),
                            p.getStatus(),
                            Boolean.TRUE.equals(p.getEdited()),
                            p.getOriginalText(),
                            p.getTranslatedText(),
                            p.getImageKey() != null ? objectStorageService.generatePresignedUrl(p.getImageKey()) : null
                    )).toList();
                    return new DocumentDetailResponse(
                            doc.getId(),
                            doc.getOwnerId(),
                            doc.getTitle(),
                            doc.getLanguage(),
                            doc.getStatus(),
                            doc.getYear(),
                            parseTags(doc.getTags()),
                            pageSummaries,
                            doc.getCreatedAt(),
                            doc.getUpdatedAt()
                    );
                }).toList();
    }

    @Transactional(readOnly = true)
    public List<SearchResultItem> searchDocuments(String ownerId, String query, String mode) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String effectiveOwner = (ownerId != null && !ownerId.isBlank()) ? ownerId : "default";
        List<PageEntity> pages;
        String matchType;
        if ("en".equalsIgnoreCase(mode) || "english".equalsIgnoreCase(mode)) {
            pages = pageRepository.searchTranslatedTextFullText(effectiveOwner, query);
            matchType = "ENGLISH_FTS";
        } else {
            pages = pageRepository.searchOriginalTextTrgm(effectiveOwner, query);
            matchType = "INDIC_TRIGRAM";
        }

        return pages.stream().map(p -> {
            String text = "en".equalsIgnoreCase(mode) ? p.getTranslatedText() : p.getOriginalText();
            String snippet = truncateSnippet(text, query, 140);
            return new SearchResultItem(
                    p.getDocument().getId(),
                    p.getDocument().getTitle(),
                    p.getPageNo(),
                    snippet,
                    matchType
            );
        }).toList();
    }

    private String truncateSnippet(String text, String query, int maxLen) {
        if (text == null) return "";
        int idx = text.toLowerCase().indexOf(query.toLowerCase());
        if (idx == -1) {
            return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
        }
        int start = Math.max(0, idx - 40);
        int end = Math.min(text.length(), idx + query.length() + 80);
        String prefix = start > 0 ? "..." : "";
        String suffix = end < text.length() ? "..." : "";
        return prefix + text.substring(start, end).trim() + suffix;
    }

    @Transactional(readOnly = true)
    public Optional<AudioLinkResponse> getDocumentAudioLink(UUID documentId, String lang) {
        return documentRepository.findById(documentId).map(doc -> {
            String suffix = (lang != null && lang.equalsIgnoreCase("orig")) ? "orig" : "en";
            String audioKey = "documents/" + documentId + "/audio/full_" + suffix + ".wav";
            String presignedUrl = objectStorageService.generatePresignedUrl(audioKey);
            return new AudioLinkResponse(documentId, suffix, presignedUrl);
        });
    }

    /**
     * Hard-delete a document and all derived artifacts:
     * 1. Validates document exists and requesting owner owns it.
     * 2. Purges all objects from MinIO with prefix documents/{documentId}/
     * 3. Deletes stage_task rows, ocr_batch rows, page rows, and document entity.
     * 4. Closes all active SSE emitters for the document.
     */
    @Transactional
    public void deleteDocument(UUID documentId, String requestingOwnerId) {
        DocumentEntity doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        String owner = (requestingOwnerId != null && !requestingOwnerId.isBlank()) ? requestingOwnerId : "default";
        boolean isOwner = doc.getOwnerId().equals(owner)
                || ("default-user".equals(doc.getOwnerId()) && "default".equals(owner))
                || ("default".equals(doc.getOwnerId()) && "default-user".equals(owner))
                || "admin".equalsIgnoreCase(owner);

        if (!isOwner) {
            throw new UnauthorizedDocumentAccessException(documentId, owner);
        }

        log.info("Executing hard-delete for document: {} (owner: {})", documentId, doc.getOwnerId());

        // 1. Purge MinIO objects under documents/{documentId}/ prefix
        try {
            objectStorageService.deletePrefix("documents/" + documentId + "/");
        } catch (Exception e) {
            log.warn("Storage purge warning for document {}: {}", documentId, e.getMessage());
        }

        // 2. Cascade delete database entities
        List<PageEntity> pages = pageRepository.findByDocumentIdOrderByPageNoAsc(documentId);
        if (!pages.isEmpty()) {
            List<UUID> pageIds = pages.stream().map(PageEntity::getId).toList();
            if (stageTaskRepository != null) {
                stageTaskRepository.deleteByPageIdIn(pageIds);
            }
            pageRepository.deleteByDocumentId(documentId);
        }

        ocrBatchRepository.deleteByDocumentId(documentId);
        documentRepository.delete(doc);

        // 3. Terminate and cleanup active SSE streams
        if (eventService != null) {
            eventService.closeEmitters(documentId);
        }

        log.info("Successfully hard-deleted document {} and all derived assets", documentId);
    }

    /**
     * Generate a read-and-listen shareable link with configurable expiration (FR11).
     */
    @Transactional(readOnly = true)
    public ShareLinkResponse generateShareLink(UUID documentId, String requestingOwnerId, int expiryHours) {
        DocumentEntity doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        String owner = (requestingOwnerId != null && !requestingOwnerId.isBlank()) ? requestingOwnerId : "default";
        boolean isOwner = doc.getOwnerId().equals(owner)
                || ("default-user".equals(doc.getOwnerId()) && "default".equals(owner))
                || ("default".equals(doc.getOwnerId()) && "default-user".equals(owner))
                || "admin".equalsIgnoreCase(owner);

        if (!isOwner) {
            throw new UnauthorizedDocumentAccessException(documentId, owner);
        }

        int hours = expiryHours > 0 && expiryHours <= 168 ? expiryHours : 24; // max 7 days, default 24h
        Instant expiresAt = Instant.now().plus(Duration.ofHours(hours));
        String rawToken = documentId + ":" + expiresAt.getEpochSecond();
        String signature = IdempotencyUtils.sha256Hex(rawToken + ":" + doc.getOwnerId());
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((rawToken + ":" + signature.substring(0, 16)).getBytes(StandardCharsets.UTF_8));
        String shareUrl = "/index.html?docId=" + documentId + "&shareToken=" + token;

        return new ShareLinkResponse(documentId, doc.getTitle(), shareUrl, token, expiresAt);
    }

    private void validateUploadFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Upload file cannot be empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size exceeds maximum allowed limit of 20MB");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Uploaded file must have a valid filename");
        }

        String ext = getFileExtension(filename).toLowerCase();
        if (!ext.equals(".pdf") && !ext.equals(".png") && !ext.equals(".jpg") && !ext.equals(".jpeg")) {
            throw new IllegalArgumentException("Unsupported file type: " + ext + ". Allowed types: PDF, PNG, JPG");
        }
    }

    private String getFileExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        return (dotIndex >= 0) ? filename.substring(dotIndex) : "";
    }

    private String serializeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize tags: {}", tags, e);
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(tagsJson, List.class);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
