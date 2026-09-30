package com.chitthi.worker;

import com.chitthi.domain.entity.DocumentEntity;
import com.chitthi.domain.entity.PageEntity;
import com.chitthi.domain.entity.StageTaskEntity;
import com.chitthi.repository.DocumentRepository;
import com.chitthi.repository.OcrBatchRepository;
import com.chitthi.repository.PageRepository;
import com.chitthi.repository.StageTaskRepository;
import com.chitthi.service.DocumentProgressEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeadLetterWorkerTest {

    @Mock
    private PageRepository pageRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private OcrBatchRepository ocrBatchRepository;

    @Mock
    private StageTaskRepository stageTaskRepository;

    @Mock
    private DocumentProgressEventService eventService;

    private ObjectMapper objectMapper;
    private DeadLetterWorker deadLetterWorker;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        deadLetterWorker = new DeadLetterWorker(
                pageRepository,
                documentRepository,
                ocrBatchRepository,
                stageTaskRepository,
                eventService,
                objectMapper
        );
    }

    @Test
    void processDeadLetter_withPageFailure_shouldMarkPageFailedAndTaskDeadLettered() {
        UUID docId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        DocumentEntity doc = new DocumentEntity(docId, "user1", "Letter", "hi", "PROCESSING", "[]", 1987);
        PageEntity page = new PageEntity(pageId, doc, 1, "doc/page_1.png", "OCR_DONE");
        StageTaskEntity task = new StageTaskEntity(UUID.randomUUID(), page, "TRANSLATE", "key1", "RUNNING");

        when(pageRepository.findById(pageId)).thenReturn(Optional.of(page));
        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(stageTaskRepository.findByPageIdAndStage(pageId, "TRANSLATE")).thenReturn(List.of(task));

        String payload = String.format("{\"pageId\":\"%s\",\"documentId\":\"%s\",\"pageNo\":1}", pageId, docId);
        MessageProperties props = new MessageProperties();
        props.setHeader("x-original-queue", "translate.queue");
        props.setHeader("x-exception-message", "Sarvam API timeout after 3 retries");
        Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), props);

        deadLetterWorker.processDeadLetter(message);

        assertThat(page.getStatus()).isEqualTo("FAILED");
        assertThat(doc.getStatus()).isEqualTo("PARTIAL");
        assertThat(task.getStatus()).isEqualTo("DEAD_LETTERED");
        assertThat(task.getLastError()).contains("Sarvam API timeout");

        verify(pageRepository).save(page);
        verify(documentRepository).save(doc);
        verify(stageTaskRepository).save(task);
        verify(eventService).emitProgress(eq(docId), eq(1), eq("TRANSLATE"), eq("FAILED"), any());
    }
}
