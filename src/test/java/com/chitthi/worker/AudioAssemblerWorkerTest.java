package com.chitthi.worker;

import com.chitthi.domain.entity.DocumentEntity;
import com.chitthi.domain.entity.PageEntity;
import com.chitthi.messaging.dto.DocumentAssembleMessage;
import com.chitthi.repository.DocumentRepository;
import com.chitthi.repository.PageRepository;
import com.chitthi.service.AudioStitcherService;
import com.chitthi.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AudioAssemblerWorkerTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private PageRepository pageRepository;

    @Mock
    private ObjectStorageService objectStorageService;

    @Mock
    private AudioStitcherService audioStitcherService;

    private AudioAssemblerWorker assemblerWorker;

    @BeforeEach
    void setUp() {
        assemblerWorker = new AudioAssemblerWorker(
                documentRepository,
                pageRepository,
                objectStorageService,
                audioStitcherService
        );
    }

    @Test
    void processDocumentAssemble_happyPath_shouldStitchTracksAndMarkComplete() {
        UUID docId = UUID.randomUUID();
        DocumentEntity doc = new DocumentEntity(docId, "user1", "Letter", "hi", "PROCESSING", "[]", 1987);

        PageEntity page1 = new PageEntity(UUID.randomUUID(), doc, 1, "doc/page_1.png", "AUDIO_DONE");
        PageEntity page2 = new PageEntity(UUID.randomUUID(), doc, 2, "doc/page_2.png", "AUDIO_DONE");

        DocumentAssembleMessage message = new DocumentAssembleMessage(docId);

        byte[] fakeWav1 = AudioStitcherService.createDummyWav(50, 22050);
        byte[] fakeWav2 = AudioStitcherService.createDummyWav(50, 22050);
        byte[] fakeStitched = AudioStitcherService.createDummyWav(100, 22050);

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));
        when(pageRepository.findByDocumentIdOrderByPageNoAsc(docId)).thenReturn(List.of(page1, page2));
        when(objectStorageService.downloadFile(contains("page_1_en.wav"))).thenReturn(fakeWav1);
        when(objectStorageService.downloadFile(contains("page_2_en.wav"))).thenReturn(fakeWav2);
        when(audioStitcherService.concatenateWavFiles(anyList())).thenReturn(fakeStitched);

        assemblerWorker.processDocumentAssemble(message);

        assertThat(doc.getStatus()).isEqualTo("COMPLETE");
        assertThat(page1.getStatus()).isEqualTo("INDEXED");
        assertThat(page2.getStatus()).isEqualTo("INDEXED");

        verify(documentRepository).save(doc);
        verify(pageRepository).saveAll(List.of(page1, page2));
        verify(objectStorageService).uploadFile(contains("full_en.wav"), eq(fakeStitched), eq("audio/wav"));
    }

    @Test
    void processDocumentAssemble_whenAlreadyComplete_shouldSkipDuplicateAssembly() {
        UUID docId = UUID.randomUUID();
        DocumentEntity doc = new DocumentEntity(docId, "user1", "Letter", "hi", "COMPLETE", "[]", 1987);
        DocumentAssembleMessage message = new DocumentAssembleMessage(docId);

        when(documentRepository.findById(docId)).thenReturn(Optional.of(doc));

        assemblerWorker.processDocumentAssemble(message);

        verifyNoInteractions(objectStorageService);
        verifyNoInteractions(audioStitcherService);
    }
}
