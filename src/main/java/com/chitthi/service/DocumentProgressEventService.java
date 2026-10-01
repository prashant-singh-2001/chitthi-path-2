package com.chitthi.service;

import com.chitthi.event.dto.PipelineProgressEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class DocumentProgressEventService {

    private static final Logger log = LoggerFactory.getLogger(DocumentProgressEventService.class);
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L; // 30 minutes

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /**
     * Registers a new SSE emitter for real-time progress updates on a document.
     */
    public SseEmitter registerEmitter(UUID documentId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.computeIfAbsent(documentId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(documentId, emitter));
        emitter.onTimeout(() -> removeEmitter(documentId, emitter));
        emitter.onError(e -> removeEmitter(documentId, emitter));

        // Send initial connection event
        try {
            emitter.send(SseEmitter.event()
                    .name("INIT")
                    .data(PipelineProgressEvent.of(documentId, null, "CONNECTION", "CONNECTED", "SSE connection established"), MediaType.APPLICATION_JSON));
            log.info("Registered new SSE emitter for documentId: {}", documentId);
        } catch (IOException e) {
            log.warn("Failed to send INIT event to emitter for documentId: {}", documentId, e);
            removeEmitter(documentId, emitter);
        }

        return emitter;
    }

    /**
     * Emits a pipeline progress event to all active emitters for a given document.
     */
    public void emitProgress(UUID documentId, Integer pageNo, String stage, String status, String message) {
        PipelineProgressEvent event = PipelineProgressEvent.of(documentId, pageNo, stage, status, message);
        emitEvent(event);
    }

    /**
     * Broadcasts a PipelineProgressEvent to all active subscribers.
     */
    public void emitEvent(PipelineProgressEvent event) {
        List<SseEmitter> documentEmitters = emitters.get(event.documentId());
        if (documentEmitters == null || documentEmitters.isEmpty()) {
            return;
        }

        log.debug("Emitting progress event for doc: {}, stage: {}, status: {}, subscribers: {}",
                event.documentId(), event.stage(), event.status(), documentEmitters.size());

        List<SseEmitter> deadEmitters = new CopyOnWriteArrayList<>();

        for (SseEmitter emitter : documentEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("PROGRESS")
                        .data(event, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                log.debug("Error sending SSE event to emitter for doc: {}, marking for removal", event.documentId());
                deadEmitters.add(emitter);
            }
        }

        if (!deadEmitters.isEmpty()) {
            documentEmitters.removeAll(deadEmitters);
            if (documentEmitters.isEmpty()) {
                emitters.remove(event.documentId());
            }
        }
    }

    public int getActiveEmitterCount(UUID documentId) {
        List<SseEmitter> list = emitters.get(documentId);
        return list != null ? list.size() : 0;
    }

    private void removeEmitter(UUID documentId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(documentId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(documentId);
            }
        }
        log.debug("Removed SSE emitter for documentId: {}", documentId);
    }

    /**
     * Closes and removes all active emitters for a document (e.g. upon document deletion).
     */
    public void closeEmitters(UUID documentId) {
        List<SseEmitter> list = emitters.remove(documentId);
        if (list != null) {
            for (SseEmitter emitter : list) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
            log.info("Closed {} SSE emitters for deleted documentId: {}", list.size(), documentId);
        }
    }
}
