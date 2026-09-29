package com.chitthi.api;

import com.chitthi.api.dto.DocumentDetailResponse;
import com.chitthi.api.dto.DocumentUploadRequest;
import com.chitthi.api.dto.DocumentUploadResponse;
import com.chitthi.service.DocumentIngestionService;
import com.chitthi.service.DocumentProgressEventService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentIngestionService ingestionService;
    private final DocumentProgressEventService eventService;

    public DocumentController(DocumentIngestionService ingestionService,
                              DocumentProgressEventService eventService) {
        this.ingestionService = ingestionService;
        this.eventService = eventService;
    }

    /**
     * Upload a document (PDF, PNG, JPG) to initiate the asynchronous digitization pipeline.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentUploadResponse> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam(value = "language", defaultValue = "hi") String language,
            @RequestParam(value = "ownerId", required = false) String ownerId,
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "tags", required = false) List<String> tags) throws IOException {

        DocumentUploadRequest request = new DocumentUploadRequest(title, language, ownerId, year, tags);
        DocumentUploadResponse response = ingestionService.ingestDocument(file, request);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * List all documents for an owner ID, ordered by creation date descending.
     */
    @GetMapping
    public ResponseEntity<List<DocumentDetailResponse>> listDocuments(
            @RequestParam(value = "ownerId", defaultValue = "default") String ownerId) {
        return ResponseEntity.ok(ingestionService.listDocuments(ownerId));
    }

    /**
     * Search documents using pg_trgm (Indic original text) or full-text search (translated English).
     */
    @GetMapping("/search")
    public ResponseEntity<List<com.chitthi.api.dto.SearchResultItem>> searchDocuments(
            @RequestParam("query") String query,
            @RequestParam(value = "mode", defaultValue = "indic") String mode,
            @RequestParam(value = "ownerId", defaultValue = "default") String ownerId) {
        return ResponseEntity.ok(ingestionService.searchDocuments(ownerId, query, mode));
    }

    /**
     * Retrieve document details, processing status, and per-page summaries with presigned image links.
     */
    @GetMapping("/{id}")
    public ResponseEntity<DocumentDetailResponse> getDocument(@PathVariable("id") UUID id) {
        return ingestionService.getDocument(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Retrieve presigned audio streaming/download URL for the full stitched audio (English or original Indic).
     */
    @GetMapping("/{id}/audio")
    public ResponseEntity<com.chitthi.api.dto.AudioLinkResponse> getDocumentAudio(
            @PathVariable("id") UUID id,
            @RequestParam(value = "lang", defaultValue = "en") String lang) {
        return ingestionService.getDocumentAudioLink(id, lang)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Stream real-time page-by-page pipeline progress events via Server-Sent Events (SSE).
     */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamDocumentEvents(@PathVariable("id") UUID id) {
        return eventService.registerEmitter(id);
    }
}
