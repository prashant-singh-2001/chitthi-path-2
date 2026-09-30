package com.chitthi.service;

import com.chitthi.api.dto.DocumentDetailResponse;
import com.chitthi.config.RabbitConfig;
import com.chitthi.domain.entity.DocumentEntity;
import com.chitthi.domain.entity.OutboxEntity;
import com.chitthi.domain.entity.PageEntity;
import com.chitthi.messaging.dto.PageTranslateMessage;
import com.chitthi.repository.DocumentRepository;
import com.chitthi.repository.OcrBatchRepository;
import com.chitthi.repository.OutboxRepository;
import com.chitthi.repository.PageRepository;
import com.chitthi.storage.ObjectStorageService;
import com.chitthi.util.IdempotencyUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EditFlowPartialInvalidationTest {

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

    private ObjectMapper objectMapper = new ObjectMapper();

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
                objectMapper,
                eventService
        );
    }

    @Test
    void editPageText_partialInvalidation_shouldUpdateOnlyTargetPageAndDispatchTranslate() {
        UUID docId = UUID.randomUUID();
        DocumentEntity doc = new DocumentEntity(docId, "user1", "Historical Diary", "hi", "COMPLETE", "[]", 1947);

        UUID page1Id = UUID.randomUUID();
        PageEntity page1 = new PageEntity(page1Id, doc, 1, "doc/page_1.png", "INDEXED");
        page1.setOriginalText("पृष्ठ १ का पाठ");
        page1.setTranslatedText("Page 1 translated");

        UUID page2Id = UUID.randomUUID();
        PageEntity page2 = new PageEntity(page2Id, doc, 2, "doc/page_2.png", "INDEXED");
        page2.setOriginalText("गलत ओसीआर पाठ");
        page2.setTranslatedText("Wrong OCR text translated");

        UUID page3Id = UUID.randomUUID();
        PageEntity page3 = new PageEntity(page3Id, doc, 3, "doc/page_3.png", "INDEXED");
        page3.setOriginalText("पृष्ठ ३ का पाठ");
        page3.setTranslatedText("Page 3 translated");

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(pageRepository.findByDocumentIdAndPageNo(docId, 2)).thenReturn(Optional.of(page2));
        when(pageRepository.findByDocumentIdOrderByPageNoAsc(docId)).thenReturn(List.of(page1, page2, page3));

        String correctedText = "संशोधित सही हस्तलिखित पाठ";
        Optional<DocumentDetailResponse> result = ingestionService.editPageText(docId, 2, correctedText);

        assertThat(result).isPresent();

        // 1. Verify Target Page 2: invalidated to OCR_DONE, edited flag set, translated text cleared
        assertThat(page2.getStatus()).isEqualTo("OCR_DONE");
        assertThat(page2.getEdited()).isTrue();
        assertThat(page2.getOriginalText()).isEqualTo(correctedText);
        assertThat(page2.getTextHash()).isEqualTo(IdempotencyUtils.sha256Hex(correctedText));
        assertThat(page2.getTranslatedText()).isNull();
        verify(pageRepository).save(page2);

        // 2. Verify Untouched Pages: page 1 and page 3 remain INDEXED and untouched
        assertThat(page1.getStatus()).isEqualTo("INDEXED");
        assertThat(page1.getEdited()).isFalse();
        assertThat(page1.getOriginalText()).isEqualTo("पृष्ठ १ का पाठ");

        assertThat(page3.getStatus()).isEqualTo("INDEXED");
        assertThat(page3.getEdited()).isFalse();
        assertThat(page3.getOriginalText()).isEqualTo("पृष्ठ ३ का पाठ");

        // 3. Verify Document status set to PROCESSING
        assertThat(doc.getStatus()).isEqualTo("PROCESSING");
        verify(documentRepository).save(doc);

        // 4. Verify RabbitMQ message was dispatched ONLY for page 2 to translate.queue
        ArgumentCaptor<PageTranslateMessage> messageCaptor = ArgumentCaptor.forClass(PageTranslateMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE_NAME),
                eq(RabbitConfig.TRANSLATE_ROUTING_KEY),
                messageCaptor.capture()
        );
        PageTranslateMessage sentMessage = messageCaptor.getValue();
        assertThat(sentMessage.documentId()).isEqualTo(docId);
        assertThat(sentMessage.pageId()).isEqualTo(page2Id);
        assertThat(sentMessage.pageNo()).isEqualTo(2);
        assertThat(sentMessage.languageCode()).isEqualTo("hi");
        assertThat(sentMessage.textHash()).isEqualTo(IdempotencyUtils.sha256Hex(correctedText));

        // 5. Verify Transactional Outbox recorded
        verify(outboxRepository, atLeastOnce()).save(any(OutboxEntity.class));

        // 6. Verify SSE progress event emitted
        verify(eventService).emitProgress(eq(docId), eq(2), eq("EDIT"), eq("COMPLETED"), contains("Archivist edited text for page 2"));
    }

    @Test
    void editPageText_whenDocumentNotFound_shouldReturnEmpty() {
        UUID docId = UUID.randomUUID();
        when(documentRepository.findById(docId)).thenReturn(Optional.empty());

        Optional<DocumentDetailResponse> result = ingestionService.editPageText(docId, 1, "नया पाठ");
        assertThat(result).isEmpty();
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void editPageText_whenPageNotFound_shouldReturnEmpty() {
        UUID docId = UUID.randomUUID();
        DocumentEntity doc = new DocumentEntity(docId, "user1", "Letter", "hi", "COMPLETE", "[]", 1987);
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(pageRepository.findByDocumentIdAndPageNo(docId, 99)).thenReturn(Optional.empty());

        Optional<DocumentDetailResponse> result = ingestionService.editPageText(docId, 99, "नया पाठ");
        assertThat(result).isEmpty();
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void editPageText_whenBlankText_shouldThrowIllegalArgumentException() {
        UUID docId = UUID.randomUUID();
        assertThatThrownBy(() -> ingestionService.editPageText(docId, 1, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Edited text cannot be empty or blank");
    }
}
