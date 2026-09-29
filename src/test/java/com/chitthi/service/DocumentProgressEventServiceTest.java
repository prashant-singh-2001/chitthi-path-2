package com.chitthi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentProgressEventServiceTest {

    private DocumentProgressEventService eventService;

    @BeforeEach
    void setUp() {
        eventService = new DocumentProgressEventService();
    }

    @Test
    void registerEmitter_shouldAddEmitterForDocument() {
        UUID docId = UUID.randomUUID();

        SseEmitter emitter = eventService.registerEmitter(docId);

        assertThat(emitter).isNotNull();
        assertThat(eventService.getActiveEmitterCount(docId)).isEqualTo(1);
    }

    @Test
    void emitProgress_shouldBroadcastToActiveEmitters() {
        UUID docId = UUID.randomUUID();
        eventService.registerEmitter(docId);

        // Emit a progress event without throwing exceptions
        eventService.emitProgress(docId, 1, "OCR", "COMPLETED", "Page 1 OCR text extracted");

        assertThat(eventService.getActiveEmitterCount(docId)).isEqualTo(1);
    }

    @Test
    void emitProgress_withNoSubscribers_shouldNotThrow() {
        UUID docId = UUID.randomUUID();
        eventService.emitProgress(docId, 1, "OCR", "COMPLETED", "Page 1 OCR text extracted");
        assertThat(eventService.getActiveEmitterCount(docId)).isEqualTo(0);
    }
}
