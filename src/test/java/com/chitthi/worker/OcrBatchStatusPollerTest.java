package com.chitthi.worker;

import com.chitthi.client.sarvam.SarvamClient;
import com.chitthi.client.sarvam.dto.DigitiseJobStatusResponse;
import com.chitthi.config.RabbitConfig;
import com.chitthi.config.SarvamProperties;
import com.chitthi.domain.entity.DocumentEntity;
import com.chitthi.domain.entity.OcrBatchEntity;
import com.chitthi.domain.entity.PageEntity;
import com.chitthi.messaging.dto.PageTranslateMessage;
import com.chitthi.repository.DocumentRepository;
import com.chitthi.repository.OcrBatchRepository;
import com.chitthi.repository.PageRepository;
import com.chitthi.repository.StageTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OcrBatchStatusPollerTest {

    @Mock
    private OcrBatchRepository ocrBatchRepository;

    @Mock
    private PageRepository pageRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private StageTaskRepository stageTaskRepository;

    @Mock
    private SarvamClient sarvamClient;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private com.chitthi.service.DocumentProgressEventService eventService;

    private ObjectMapper objectMapper;
    private SarvamProperties sarvamProperties;
    private OcrBatchStatusPoller poller;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        sarvamProperties = new SarvamProperties(
                "https://api.sarvam.ai",
                "test-key",
                5000L,
                30,
                5000L,
                30000L,
                "/doc-ai/v1/job/digitise",
                "/doc-ai/v1/job/{jobId}/status",
                "/translate",
                "/text-to-speech",
                "shubh",
                "bulbul:v3",
                "sarvam-translate:v1"
        );
        poller = new OcrBatchStatusPoller(
                ocrBatchRepository,
                pageRepository,
                documentRepository,
                stageTaskRepository,
                sarvamClient,
                sarvamProperties,
                rabbitTemplate,
                objectMapper,
                eventService
        );
    }

    private byte[] createTestZipArchive() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // Page 1 json
            ZipEntry entry1 = new ZipEntry("metadata/page_001.json");
            zos.putNextEntry(entry1);
            zos.write("{\"page_number\": 1, \"text\": \"नमस्ते नानाजी, आपका पत्र मिला।\"}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // Page 2 json
            ZipEntry entry2 = new ZipEntry("metadata/page_002.json");
            zos.putNextEntry(entry2);
            zos.write("{\"page_number\": 2, \"text\": \"जयपुर का पुराना मकान अब ठीक हो गया है।\"}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    @Test
    void extractPageTextsFromZip_shouldParsePerPageJson() throws IOException {
        byte[] zipBytes = createTestZipArchive();

        Map<Integer, String> result = poller.extractPageTextsFromZip(zipBytes, 1, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(1)).isEqualTo("नमस्ते नानाजी, आपका पत्र मिला।");
        assertThat(result.get(2)).isEqualTo("जयपुर का पुराना मकान अब ठीक हो गया है।");
    }

    @Test
    void pollBatchStatus_whenCompleted_shouldUpdatePagesAndDispatchTranslate() throws IOException {
        UUID docId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        DocumentEntity doc = new DocumentEntity(docId, "user1", "Letter", "hi", "PROCESSING", "[]", 1987);
        OcrBatchEntity batch = new OcrBatchEntity(batchId, doc, "1-2", "job-999", "RUNNING");

        PageEntity page1 = new PageEntity(UUID.randomUUID(), doc, 1, "doc/page_1.png", "PENDING");
        PageEntity page2 = new PageEntity(UUID.randomUUID(), doc, 2, "doc/page_2.png", "PENDING");

        DigitiseJobStatusResponse completedStatus = new DigitiseJobStatusResponse(
                "job-999", "completed", "https://api.sarvam.ai/download/job-999.zip", null
        );

        when(sarvamClient.getJobStatus("job-999")).thenReturn(completedStatus);
        when(sarvamClient.downloadJobResult("https://api.sarvam.ai/download/job-999.zip"))
                .thenReturn(createTestZipArchive());
        when(pageRepository.findByDocumentIdAndPageNoBetweenOrderByPageNoAsc(docId, 1, 2))
                .thenReturn(List.of(page1, page2));

        poller.pollBatchStatus(batch);

        assertThat(batch.getStatus()).isEqualTo("COMPLETED");
        verify(ocrBatchRepository).save(batch);

        assertThat(page1.getStatus()).isEqualTo("OCR_DONE");
        assertThat(page1.getOriginalText()).isEqualTo("नमस्ते नानाजी, आपका पत्र मिला।");
        assertThat(page1.getTextHash()).isNotEmpty();

        assertThat(page2.getStatus()).isEqualTo("OCR_DONE");
        assertThat(page2.getOriginalText()).isEqualTo("जयपुर का पुराना मकान अब ठीक हो गया है।");

        verify(pageRepository, times(2)).save(any(PageEntity.class));
        verify(stageTaskRepository, times(2)).save(any());

        ArgumentCaptor<PageTranslateMessage> messageCaptor = ArgumentCaptor.forClass(PageTranslateMessage.class);
        verify(rabbitTemplate, times(2)).convertAndSend(
                eq(RabbitConfig.EXCHANGE_NAME),
                eq(RabbitConfig.TRANSLATE_ROUTING_KEY),
                messageCaptor.capture()
        );

        List<PageTranslateMessage> messages = messageCaptor.getAllValues();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).pageNo()).isEqualTo(1);
        assertThat(messages.get(0).languageCode()).isEqualTo("hi");
        assertThat(messages.get(1).pageNo()).isEqualTo(2);
    }

    @Test
    void pollBatchStatus_whenStillRunning_shouldIncrementPollCountAndBackoff() {
        UUID docId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        DocumentEntity doc = new DocumentEntity(docId, "user1", "Letter", "hi", "PROCESSING", "[]", 1987);
        OcrBatchEntity batch = new OcrBatchEntity(batchId, doc, "1-1", "job-111", "RUNNING");
        batch.setPollCount(2);

        DigitiseJobStatusResponse runningStatus = new DigitiseJobStatusResponse(
                "job-111", "running", null, null
        );
        when(sarvamClient.getJobStatus("job-111")).thenReturn(runningStatus);

        poller.pollBatchStatus(batch);

        assertThat(batch.getStatus()).isEqualTo("RUNNING");
        assertThat(batch.getPollCount()).isEqualTo(3);
        assertThat(batch.getNextPollAt()).isNotNull();
        verify(ocrBatchRepository).save(batch);
        verifyNoInteractions(rabbitTemplate);
    }
}
