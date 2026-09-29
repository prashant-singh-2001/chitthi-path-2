package com.chitthi.worker;

import com.chitthi.client.sarvam.SarvamClient;
import com.chitthi.client.sarvam.dto.TranslateResponse;
import com.chitthi.config.RabbitConfig;
import com.chitthi.domain.entity.ApiCallEntity;
import com.chitthi.domain.entity.DocumentEntity;
import com.chitthi.domain.entity.PageEntity;
import com.chitthi.messaging.dto.PageTtsMessage;
import com.chitthi.messaging.dto.PageTranslateMessage;
import com.chitthi.repository.ApiCallRepository;
import com.chitthi.repository.PageRepository;
import com.chitthi.repository.StageTaskRepository;
import com.chitthi.service.TextChunkingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TranslateWorkerTest {

    @Mock
    private PageRepository pageRepository;

    @Mock
    private StageTaskRepository stageTaskRepository;

    @Mock
    private ApiCallRepository apiCallRepository;

    @Mock
    private SarvamClient sarvamClient;

    @Mock
    private TextChunkingService textChunkingService;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private com.chitthi.service.DocumentProgressEventService eventService;

    private TranslateWorker translateWorker;

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
    }

    @Test
    void processPageTranslate_happyPath_shouldTranslateAndDispatchTts() {
        UUID docId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        DocumentEntity doc = new DocumentEntity(docId, "user1", "Letter", "hi", "PROCESSING", "[]", 1987);
        PageEntity page = new PageEntity(pageId, doc, 1, "doc/page_1.png", "OCR_DONE");
        page.setOriginalText("नानाजी का पत्र मिला।");

        PageTranslateMessage message = new PageTranslateMessage(pageId, docId, 1, "hi", "hash123");

        when(pageRepository.findById(pageId)).thenReturn(Optional.of(page));
        when(textChunkingService.chunkText(eq("नानाजी का पत्र मिला।"), anyInt()))
                .thenReturn(List.of("नानाजी का पत्र मिला।"));
        when(sarvamClient.translate(any()))
                .thenReturn(new TranslateResponse("Received Nanaji's letter."));

        translateWorker.processPageTranslate(message);

        assertThat(page.getStatus()).isEqualTo("TRANSLATED");
        assertThat(page.getTranslatedText()).isEqualTo("Received Nanaji's letter.");
        verify(pageRepository).save(page);
        verify(stageTaskRepository).save(any());

        ArgumentCaptor<ApiCallEntity> apiCallCaptor = ArgumentCaptor.forClass(ApiCallEntity.class);
        verify(apiCallRepository).save(apiCallCaptor.capture());
        ApiCallEntity savedCall = apiCallCaptor.getValue();
        assertThat(savedCall.getEndpoint()).isEqualTo("/translate");
        assertThat(savedCall.getUnits()).isEqualTo(BigDecimal.valueOf("नानाजी का पत्र मिला।".length()));

        ArgumentCaptor<PageTtsMessage> ttsCaptor = ArgumentCaptor.forClass(PageTtsMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE_NAME),
                eq(RabbitConfig.TTS_ROUTING_KEY),
                ttsCaptor.capture()
        );
        PageTtsMessage ttsMessage = ttsCaptor.getValue();
        assertThat(ttsMessage.pageId()).isEqualTo(pageId);
        assertThat(ttsMessage.pageNo()).isEqualTo(1);
    }

    @Test
    void processPageTranslate_whenAlreadyTranslated_shouldSkipDuplicateApiCall() {
        UUID docId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        DocumentEntity doc = new DocumentEntity(docId, "user1", "Letter", "hi", "PROCESSING", "[]", 1987);
        PageEntity page = new PageEntity(pageId, doc, 1, "doc/page_1.png", "TRANSLATED");
        page.setOriginalText("नानाजी का पत्र");
        page.setTranslatedText("Nanaji's letter");

        PageTranslateMessage message = new PageTranslateMessage(pageId, docId, 1, "hi", "hash123");
        when(pageRepository.findById(pageId)).thenReturn(Optional.of(page));

        translateWorker.processPageTranslate(message);

        verifyNoInteractions(sarvamClient);
        verifyNoInteractions(apiCallRepository);
        verify(rabbitTemplate).convertAndSend(eq(RabbitConfig.EXCHANGE_NAME), eq(RabbitConfig.TTS_ROUTING_KEY), any(PageTtsMessage.class));
    }
}
