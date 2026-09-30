package com.chitthi.service;

import com.chitthi.api.dto.*;
import com.chitthi.config.RabbitConfig;
import com.chitthi.domain.entity.DocumentEntity;
import com.chitthi.domain.entity.OcrBatchEntity;
import com.chitthi.domain.entity.OutboxEntity;
import com.chitthi.domain.entity.PageEntity;
import com.chitthi.messaging.dto.OcrBatchMessage;
import com.chitthi.repository.DocumentRepository;
import com.chitthi.repository.OcrBatchRepository;
import com.chitthi.repository.OutboxRepository;
import com.chitthi.repository.PageRepository;
import com.chitthi.storage.ObjectStorageService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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

    public DocumentIngestionService(DocumentRepository documentRepository,
                                  PageRepository pageRepository,
                                  OcrBatchRepository ocrBatchRepository,
                                  OutboxRepository outboxRepository,
                                  ObjectStorageService objectStorageService,
                                  PdfSplitterService pdfSplitterService,
                                  RabbitTemplate rabbitTemplate,
                                  ObjectMapper objectMapper,
                                  DocumentProgressEventService eventService) {
        this.documentRepository = documentRepository;
        this.pageRepository = pageRepository;
        this.ocrBatchRepository = ocrBatchRepository;
        this.outboxRepository = outboxRepository;
        this.objectStorageService = objectStorageService;
        this.pdfSplitterService = pdfSplitterService;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.eventService = eventService;
    }

    @Transactional
    public DocumentUploadResponse ingestDocument(MultipartFile file, DocumentUploadRequest request) throws IOException {
        validateUploadFile(file);

        UUID documentId = UUID.randomUUID();
        String ownerId = (request.ownerId() != null && !request.ownerId().isBlank())
                ? request.ownerId() : "default-user";

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
