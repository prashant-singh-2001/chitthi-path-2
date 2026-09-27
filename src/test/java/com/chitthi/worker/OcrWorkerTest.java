package com.chitthi.worker;

import com.chitthi.client.sarvam.SarvamClient;
import com.chitthi.client.sarvam.dto.DigitiseJobResponse;
import com.chitthi.domain.entity.ApiCallEntity;
import com.chitthi.domain.entity.DocumentEntity;
import com.chitthi.domain.entity.OcrBatchEntity;
import com.chitthi.messaging.dto.OcrBatchMessage;
import com.chitthi.repository.ApiCallRepository;
import com.chitthi.repository.OcrBatchRepository;
import com.chitthi.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OcrWorkerTest {

    @Mock
    private OcrBatchRepository ocrBatchRepository;

    @Mock
    private ApiCallRepository apiCallRepository;

    @Mock
    private ObjectStorageService objectStorageService;

    @Mock
    private SarvamClient sarvamClient;

    private OcrWorker ocrWorker;

    @BeforeEach
    void setUp() {
        ocrWorker = new OcrWorker(ocrBatchRepository, apiCallRepository, objectStorageService, sarvamClient);
    }

    @Test
    void processOcrBatch_happyPath_shouldSubmitJobAndUpdateState() {
        UUID docId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        DocumentEntity doc = new DocumentEntity(docId, "user1", "Letter", "hi", "PROCESSING", "[]", 1987);
        OcrBatchEntity batch = new OcrBatchEntity(batchId, doc, "1-10", null, "PENDING");

        OcrBatchMessage message = new OcrBatchMessage(
                batchId, docId, "1-10", "documents/" + docId + "/batches/batch_1-10.pdf", "hi"
        );

        byte[] fakePdf = "Fake PDF Content".getBytes();
        when(ocrBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        when(objectStorageService.downloadFile(message.storageKey())).thenReturn(fakePdf);
        when(sarvamClient.submitDigitiseJob(eq(fakePdf), eq("batch_1-10.pdf"), eq("hi")))
                .thenReturn(new DigitiseJobResponse("sarvam-job-456"));

        ocrWorker.processOcrBatch(message);

        assertThat(batch.getStatus()).isEqualTo("RUNNING");
        assertThat(batch.getSarvamJobId()).isEqualTo("sarvam-job-456");
        assertThat(batch.getNextPollAt()).isNotNull();
        verify(ocrBatchRepository).save(batch);

        ArgumentCaptor<ApiCallEntity> apiCallCaptor = ArgumentCaptor.forClass(ApiCallEntity.class);
        verify(apiCallRepository).save(apiCallCaptor.capture());

        ApiCallEntity savedCall = apiCallCaptor.getValue();
        assertThat(savedCall.getDocumentId()).isEqualTo(docId);
        assertThat(savedCall.getEndpoint()).isEqualTo("/doc-ai/v1/job/digitise");
        assertThat(savedCall.getUnits()).isEqualByComparingTo(BigDecimal.valueOf(10)); // 1-10 is 10 pages
        assertThat(savedCall.getEstCostInr()).isEqualByComparingTo(BigDecimal.valueOf(5.0000)); // 10 * 0.50
    }

    @Test
    void processOcrBatch_whenAlreadyRunning_shouldSkipDuplicatePaidCall() {
        UUID docId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        DocumentEntity doc = new DocumentEntity(docId, "user1", "Letter", "hi", "PROCESSING", "[]", 1987);
        OcrBatchEntity batch = new OcrBatchEntity(batchId, doc, "1-10", "sarvam-job-456", "RUNNING");

        OcrBatchMessage message = new OcrBatchMessage(
                batchId, docId, "1-10", "documents/" + docId + "/batches/batch_1-10.pdf", "hi"
        );

        when(ocrBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));

        ocrWorker.processOcrBatch(message);

        verifyNoInteractions(sarvamClient);
        verifyNoInteractions(objectStorageService);
        verifyNoInteractions(apiCallRepository);
    }
}
