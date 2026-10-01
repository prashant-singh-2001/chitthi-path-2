package com.chitthi.service;

import com.chitthi.api.dto.ShareLinkResponse;
import com.chitthi.domain.entity.DocumentEntity;
import com.chitthi.domain.entity.PageEntity;
import com.chitthi.exception.DocumentNotFoundException;
import com.chitthi.exception.UnauthorizedDocumentAccessException;
import com.chitthi.repository.DocumentRepository;
import com.chitthi.repository.OcrBatchRepository;
import com.chitthi.repository.OutboxRepository;
import com.chitthi.repository.PageRepository;
import com.chitthi.repository.StageTaskRepository;
import com.chitthi.storage.ObjectStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentHardDeleteTest {

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
    private DocumentProgressEventService eventService;
    @Mock
    private UsageLedgerService usageLedgerService;
    @Mock
    private StageTaskRepository stageTaskRepository;

    private DocumentIngestionService ingestionService;

    @BeforeEach
    void setUp() {
        ingestionService = new DocumentIngestionService(
                documentRepository,
                pageRepository,
                ocrBatchRepository,
                outboxRepository,
                objectStorageService,
                pdfSplitterService,
                rabbitTemplate,
                new ObjectMapper(),
                eventService,
                usageLedgerService,
                stageTaskRepository
        );
    }

    @Test
    void deleteDocument_whenAuthorizedOwner_shouldPurgeStorageAndDatabase() {
        UUID docId = UUID.randomUUID();
        String ownerId = "archivist-1";
        DocumentEntity doc = new DocumentEntity(docId, ownerId, "Old Letter", "hi", "COMPLETE", "[]", 1950);

        PageEntity p1 = new PageEntity(UUID.randomUUID(), doc, 1, "k1", "INDEXED");
        PageEntity p2 = new PageEntity(UUID.randomUUID(), doc, 2, "k2", "INDEXED");

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(pageRepository.findByDocumentIdOrderByPageNoAsc(docId)).thenReturn(List.of(p1, p2));

        ingestionService.deleteDocument(docId, ownerId);

        // 1. MinIO prefix purge
        verify(objectStorageService).deletePrefix("documents/" + docId + "/");

        // 2. Cascade delete database entities
        verify(stageTaskRepository).deleteByPageIdIn(List.of(p1.getId(), p2.getId()));
        verify(pageRepository).deleteByDocumentId(docId);
        verify(ocrBatchRepository).deleteByDocumentId(docId);
        verify(documentRepository).delete(doc);

        // 3. SSE stream teardown
        verify(eventService).closeEmitters(docId);
    }

    @Test
    void deleteDocument_whenUnauthorizedOwner_shouldThrowUnauthorizedException() {
        UUID docId = UUID.randomUUID();
        DocumentEntity doc = new DocumentEntity(docId, "userA", "Secret Letter", "hi", "COMPLETE", "[]", 1960);

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> ingestionService.deleteDocument(docId, "userB"))
                .isInstanceOf(UnauthorizedDocumentAccessException.class)
                .hasMessageContaining("User 'userB' is not authorized");

        verifyNoInteractions(objectStorageService);
        verify(documentRepository, never()).delete(any());
        verify(pageRepository, never()).deleteByDocumentId(any());
    }

    @Test
    void deleteDocument_whenNotFound_shouldThrowDocumentNotFoundException() {
        UUID docId = UUID.randomUUID();
        when(documentRepository.findById(docId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ingestionService.deleteDocument(docId, "default"))
                .isInstanceOf(DocumentNotFoundException.class)
                .hasMessageContaining("was not found");

        verifyNoInteractions(objectStorageService);
        verify(documentRepository, never()).delete(any());
    }

    @Test
    void generateShareLink_whenValid_shouldCreateExpiringToken() {
        UUID docId = UUID.randomUUID();
        String ownerId = "archivist-1";
        DocumentEntity doc = new DocumentEntity(docId, ownerId, "Rare Manuscript", "hi", "COMPLETE", "[]", 1930);

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        ShareLinkResponse response = ingestionService.generateShareLink(docId, ownerId, 48);

        assertThat(response.documentId()).isEqualTo(docId);
        assertThat(response.title()).isEqualTo("Rare Manuscript");
        assertThat(response.shareUrl()).contains(docId.toString());
        assertThat(response.token()).isNotBlank();
        assertThat(response.expiresAt()).isAfter(Instant.now().plusSeconds(47 * 3600));
    }
}
