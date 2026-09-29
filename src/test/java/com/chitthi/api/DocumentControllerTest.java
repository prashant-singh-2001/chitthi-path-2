package com.chitthi.api;

import com.chitthi.api.dto.DocumentDetailResponse;
import com.chitthi.api.dto.DocumentUploadResponse;
import com.chitthi.api.dto.PageSummaryResponse;
import com.chitthi.service.DocumentIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentIngestionService ingestionService;

    @MockBean
    private com.chitthi.service.DocumentProgressEventService eventService;

    @Test
    void uploadDocument_shouldReturnAccepted() throws Exception {
        UUID docId = UUID.randomUUID();
        DocumentUploadResponse uploadResponse = new DocumentUploadResponse(
                docId, "Family Letter 1987", "hi", "PROCESSING", 2, 1, Instant.now()
        );

        when(ingestionService.ingestDocument(any(), any())).thenReturn(uploadResponse);

        MockMultipartFile file = new MockMultipartFile(
                "file", "letter.pdf", "application/pdf", "%PDF-1.4 test".getBytes()
        );

        mockMvc.perform(multipart("/api/documents")
                        .file(file)
                        .param("title", "Family Letter 1987")
                        .param("language", "hi")
                        .param("year", "1987"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.documentId").value(docId.toString()))
                .andExpect(jsonPath("$.title").value("Family Letter 1987"))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void getDocument_whenExists_shouldReturnDetail() throws Exception {
        UUID docId = UUID.randomUUID();
        PageSummaryResponse page1 = new PageSummaryResponse(
                UUID.randomUUID(), 1, "OCR_DONE", false, "Hindi text", null, "http://presigned/page1.png"
        );
        DocumentDetailResponse detailResponse = new DocumentDetailResponse(
                docId, "user1", "Family Letter", "hi", "PROCESSING", 1987, List.of("family", "jaipur"),
                List.of(page1), Instant.now(), Instant.now()
        );

        when(ingestionService.getDocument(docId)).thenReturn(Optional.of(detailResponse));

        mockMvc.perform(get("/api/documents/" + docId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(docId.toString()))
                .andExpect(jsonPath("$.title").value("Family Letter"))
                .andExpect(jsonPath("$.pages[0].pageNo").value(1))
                .andExpect(jsonPath("$.pages[0].imageUrl").value("http://presigned/page1.png"));
    }

    @Test
    void getDocument_whenNotFound_shouldReturn404() throws Exception {
        UUID docId = UUID.randomUUID();
        when(ingestionService.getDocument(docId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/documents/" + docId))
                .andExpect(status().isNotFound());
    }

    @Test
    void getDocumentAudio_whenExists_shouldReturnAudioLink() throws Exception {
        UUID docId = UUID.randomUUID();
        com.chitthi.api.dto.AudioLinkResponse audioLink = new com.chitthi.api.dto.AudioLinkResponse(
                docId, "en", "http://minio:9100/chitthi-documents/documents/" + docId + "/audio/full_en.wav?token=abc"
        );

        when(ingestionService.getDocumentAudioLink(docId, "en")).thenReturn(Optional.of(audioLink));

        mockMvc.perform(get("/api/documents/" + docId + "/audio?lang=en")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(docId.toString()))
                .andExpect(jsonPath("$.language").value("en"))
                .andExpect(jsonPath("$.audioUrl").value(audioLink.audioUrl()));
    }

    @Test
    void getDocumentAudio_whenNotFound_shouldReturn404() throws Exception {
        UUID docId = UUID.randomUUID();
        when(ingestionService.getDocumentAudioLink(docId, "en")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/documents/" + docId + "/audio?lang=en"))
                .andExpect(status().isNotFound());
    }

    @Test
    void streamDocumentEvents_shouldReturnSseEmitter() throws Exception {
        UUID docId = UUID.randomUUID();
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter();
        when(eventService.registerEmitter(docId)).thenReturn(emitter);

        mockMvc.perform(get("/api/documents/" + docId + "/events"))
                .andExpect(status().isOk());
    }

    @Test
    void listDocuments_shouldReturnList() throws Exception {
        UUID docId = UUID.randomUUID();
        DocumentDetailResponse doc = new DocumentDetailResponse(
                docId, "default", "Grandfather Letter", "hi", "COMPLETE", 1974,
                List.of("family"), List.of(), Instant.now(), Instant.now()
        );
        when(ingestionService.listDocuments("default")).thenReturn(List.of(doc));

        mockMvc.perform(get("/api/documents?ownerId=default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(docId.toString()))
                .andExpect(jsonPath("$[0].title").value("Grandfather Letter"));
    }

    @Test
    void searchDocuments_shouldReturnResults() throws Exception {
        UUID docId = UUID.randomUUID();
        com.chitthi.api.dto.SearchResultItem item = new com.chitthi.api.dto.SearchResultItem(
                docId, "Grandfather Letter", 1, "नानाजी का पत्र मिला...", "INDIC_TRIGRAM"
        );
        when(ingestionService.searchDocuments("default", "पत्र", "indic")).thenReturn(List.of(item));

        mockMvc.perform(get("/api/documents/search?query=पत्र&mode=indic&ownerId=default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].documentId").value(docId.toString()))
                .andExpect(jsonPath("$[0].pageNo").value(1))
                .andExpect(jsonPath("$[0].matchType").value("INDIC_TRIGRAM"));
    }
}

