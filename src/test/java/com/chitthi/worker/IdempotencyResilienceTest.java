package com.chitthi.worker;

import com.chitthi.client.sarvam.SarvamClient;
import com.chitthi.config.RabbitConfig;
import com.chitthi.domain.entity.DocumentEntity;
import com.chitthi.domain.entity.PageEntity;
import com.chitthi.domain.entity.StageTaskEntity;
import com.chitthi.messaging.dto.PageTranslateMessage;
import com.chitthi.messaging.dto.PageTtsMessage;
import com.chitthi.repository.ApiCallRepository;
import com.chitthi.repository.PageRepository;
import com.chitthi.repository.StageTaskRepository;
import com.chitthi.service.AudioStitcherService;
import com.chitthi.service.DocumentProgressEventService;
import com.chitthi.service.TextChunkingService;
import com.chitthi.storage.ObjectStorageService;
import com.chitthi.util.IdempotencyUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyResilienceTest {

    @Mock
    private PageRepository pageRepository;

    @Mock
    private StageTaskRepository stageTaskRepository;

    @Mock
    private ApiCallRepository apiCallRepository;

    @Mock
    private ObjectStorageService objectStorageService;

    @Mock
    private SarvamClient sarvamClient;

    @Mock
    private TextChunkingService textChunkingService;

    @Mock
    private AudioStitcherService audioStitcherService;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private DocumentProgressEventService eventService;

    private TranslateWorker translateWorker;
    private TtsWorker ttsWorker;

    @BeforeEach
    void setUp() {
        translateWorker = new TranslateWorker(
                pageRepository,
                stageTaskRepository,
                apiCallRepository,
                sarvamClient,
                textChunkingService,
                rabbitTemplate,
                eventService
        );

        ttsWorker = new TtsWorker(
                pageRepository,
                stageTaskRepository,
                apiCallRepository,
                objectStorageService,
                sarvamClient,
                textChunkingService,
                audioStitcherService,
                rabbitTemplate,
                eventService
        );
    }

    @Test
    void translateWorker_whenDuplicateMessageDelivered_shouldSkipPaidApiCall() {
        UUID docId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        String textHash = "hash123";
        DocumentEntity doc = new DocumentEntity(docId, "user1", "Letter", "hi", "PROCESSING", "[]", 1987);
        PageEntity page = new PageEntity(pageId, doc, 1, "doc/page_1.png", "OCR_DONE");
        page.setOriginalText("प्रिय नानाजी, प्रणाम।");

        PageTranslateMessage message = new PageTranslateMessage(pageId, docId, 1, "hi", textHash);

        String idempotencyKey = IdempotencyUtils.buildKey("TRANSLATE", docId, 1, textHash);
        StageTaskEntity completedTask = new StageTaskEntity(UUID.randomUUID(), page, "TRANSLATE", idempotencyKey, "COMPLETED");

        when(pageRepository.findById(pageId)).thenReturn(Optional.of(page));
        when(stageTaskRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(completedTask));

        // Act: deliver duplicate message
        translateWorker.processPageTranslate(message);

        // Assert: 0 calls made to Sarvam Translate!
        verify(sarvamClient, never()).translate(any());
        // Downstream dispatch still called to maintain pipeline forward progress
        verify(rabbitTemplate).convertAndSend(eq(RabbitConfig.EXCHANGE_NAME), eq(RabbitConfig.TTS_ROUTING_KEY), any(PageTtsMessage.class));
    }

    @Test
    void ttsWorker_whenDuplicateMessageDelivered_shouldSkipPaidApiCall() {
        UUID docId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        String textHash = "hash456";
        DocumentEntity doc = new DocumentEntity(docId, "user1", "Letter", "hi", "PROCESSING", "[]", 1987);
        PageEntity page = new PageEntity(pageId, doc, 1, "doc/page_1.png", "TRANSLATED");
        page.setOriginalText("नमस्ते");
        page.setTranslatedText("Hello");

        PageTtsMessage message = new PageTtsMessage(pageId, docId, 1, "hi", textHash);

        String idempotencyKey = IdempotencyUtils.buildKey("TTS", docId, 1, textHash);
        StageTaskEntity completedTask = new StageTaskEntity(UUID.randomUUID(), page, "TTS", idempotencyKey, "COMPLETED");

        when(pageRepository.findById(pageId)).thenReturn(Optional.of(page));
        when(stageTaskRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(completedTask));

        // Act: deliver duplicate message
        ttsWorker.processPageTts(message);

        // Assert: 0 calls made to Sarvam Bulbul TTS!
        verify(sarvamClient, never()).textToSpeech(any(), any());
    }
}
