package com.chitthi.worker;

import com.chitthi.client.sarvam.SarvamClient;
import com.chitthi.client.sarvam.dto.TtsResponse;
import com.chitthi.config.RabbitConfig;
import com.chitthi.domain.entity.DocumentEntity;
import com.chitthi.domain.entity.PageEntity;
import com.chitthi.messaging.dto.DocumentAssembleMessage;
import com.chitthi.messaging.dto.PageTtsMessage;
import com.chitthi.repository.ApiCallRepository;
import com.chitthi.repository.PageRepository;
import com.chitthi.repository.StageTaskRepository;
import com.chitthi.service.AudioStitcherService;
import com.chitthi.service.TextChunkingService;
import com.chitthi.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TtsWorkerTest {

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
    private com.chitthi.service.DocumentProgressEventService eventService;

    private TtsWorker ttsWorker;

    @BeforeEach
    void setUp() {
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
    void processPageTts_happyPath_shouldSynthesizeAudioAndTriggerAssemble() {
        UUID docId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        DocumentEntity doc = new DocumentEntity(docId, "user1", "Letter", "hi", "PROCESSING", "[]", 1987);
        PageEntity page = new PageEntity(pageId, doc, 1, "doc/page_1.png", "TRANSLATED");
        page.setOriginalText("नमस्ते नानाजी");
        page.setTranslatedText("Hello Nanaji");

        PageTtsMessage message = new PageTtsMessage(pageId, docId, 1, "hi", "hash123");

        byte[] fakeWavBytes = AudioStitcherService.createDummyWav(50, 22050);
        String base64Wav = Base64.getEncoder().encodeToString(fakeWavBytes);

        when(pageRepository.findById(pageId)).thenReturn(Optional.of(page));
        when(textChunkingService.chunkText(anyString(), anyInt()))
                .thenAnswer(inv -> Collections.singletonList(inv.getArgument(0, String.class)));
        when(sarvamClient.textToSpeech(anyList(), anyString()))
                .thenReturn(new TtsResponse(List.of(base64Wav)));
        when(audioStitcherService.concatenateWavFiles(anyList())).thenReturn(fakeWavBytes);
        when(pageRepository.findByDocumentIdOrderByPageNoAsc(docId)).thenReturn(List.of(page));

        ttsWorker.processPageTts(message);

        assertThat(page.getStatus()).isEqualTo("AUDIO_DONE");
        verify(pageRepository).save(page);
        verify(stageTaskRepository).save(any());

        // Verify uploads for both English and Original Hindi audio
        verify(objectStorageService).uploadFile(contains("page_1_en.wav"), eq(fakeWavBytes), eq("audio/wav"));
        verify(objectStorageService).uploadFile(contains("page_1_orig.wav"), eq(fakeWavBytes), eq("audio/wav"));

        // Verify assemble queue message dispatched since page 1 is the only page
        ArgumentCaptor<DocumentAssembleMessage> assembleCaptor = ArgumentCaptor.forClass(DocumentAssembleMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE_NAME),
                eq(RabbitConfig.ASSEMBLE_ROUTING_KEY),
                assembleCaptor.capture()
        );
        assertThat(assembleCaptor.getValue().documentId()).isEqualTo(docId);
    }

    @Test
    void processPageTts_whenAlreadyAudioDone_shouldSkipDuplicateSynthesis() {
        UUID docId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        DocumentEntity doc = new DocumentEntity(docId, "user1", "Letter", "hi", "PROCESSING", "[]", 1987);
        PageEntity page = new PageEntity(pageId, doc, 1, "doc/page_1.png", "AUDIO_DONE");

        PageTtsMessage message = new PageTtsMessage(pageId, docId, 1, "hi", "hash123");
        when(pageRepository.findById(pageId)).thenReturn(Optional.of(page));
        when(pageRepository.findByDocumentIdOrderByPageNoAsc(docId)).thenReturn(List.of(page));

        ttsWorker.processPageTts(message);

        verifyNoInteractions(sarvamClient);
        verifyNoInteractions(objectStorageService);
    }
}
