package com.chitthi.worker;

import com.chitthi.client.sarvam.SarvamClient;
import com.chitthi.client.sarvam.dto.TtsResponse;
import com.chitthi.config.RabbitConfig;
import com.chitthi.domain.entity.ApiCallEntity;
import com.chitthi.domain.entity.PageEntity;
import com.chitthi.domain.entity.StageTaskEntity;
import com.chitthi.messaging.dto.DocumentAssembleMessage;
import com.chitthi.messaging.dto.PageTtsMessage;
import com.chitthi.repository.ApiCallRepository;
import com.chitthi.repository.PageRepository;
import com.chitthi.repository.StageTaskRepository;
import com.chitthi.service.AudioStitcherService;
import com.chitthi.service.DocumentProgressEventService;
import com.chitthi.service.TextChunkingService;
import com.chitthi.storage.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Component
public class TtsWorker {

    private static final Logger log = LoggerFactory.getLogger(TtsWorker.class);
    private static final int MAX_TTS_CHARS_PER_CHUNK = 2500;
    private static final BigDecimal COST_PER_CHAR_INR = new BigDecimal("0.0030"); // ₹30 per 10k chars

    private static final Set<String> BULBUL_SUPPORTED_LANGUAGES = Set.of(
            "hi-in", "bn-in", "gu-in", "kn-in", "ml-in", "mr-in",
            "od-in", "pa-in", "ta-in", "te-in", "en-in",
            "hi", "bn", "gu", "kn", "ml", "mr", "od", "pa", "ta", "te", "en"
    );

    private final PageRepository pageRepository;
    private final StageTaskRepository stageTaskRepository;
    private final ApiCallRepository apiCallRepository;
    private final ObjectStorageService objectStorageService;
    private final SarvamClient sarvamClient;
    private final TextChunkingService textChunkingService;
    private final AudioStitcherService audioStitcherService;
    private final RabbitTemplate rabbitTemplate;
    private final DocumentProgressEventService eventService;

    public TtsWorker(PageRepository pageRepository,
                     StageTaskRepository stageTaskRepository,
                     ApiCallRepository apiCallRepository,
                     ObjectStorageService objectStorageService,
                     SarvamClient sarvamClient,
                     TextChunkingService textChunkingService,
                     AudioStitcherService audioStitcherService,
                     RabbitTemplate rabbitTemplate,
                     DocumentProgressEventService eventService) {
        this.pageRepository = pageRepository;
        this.stageTaskRepository = stageTaskRepository;
        this.apiCallRepository = apiCallRepository;
        this.objectStorageService = objectStorageService;
        this.sarvamClient = sarvamClient;
        this.textChunkingService = textChunkingService;
        this.audioStitcherService = audioStitcherService;
        this.rabbitTemplate = rabbitTemplate;
        this.eventService = eventService;
    }

    @RabbitListener(queues = RabbitConfig.TTS_QUEUE)
    @Transactional
    public void processPageTts(PageTtsMessage message) {
        log.info("Received TTS task for pageId: {}, docId: {}, pageNo: {}",
                message.pageId(), message.documentId(), message.pageNo());

        Optional<PageEntity> pageOpt = pageRepository.findById(message.pageId());
        if (pageOpt.isEmpty()) {
            log.warn("Page {} not found in database, skipping", message.pageId());
            return;
        }

        PageEntity page = pageOpt.get();

        String idempotencyKey = com.chitthi.util.IdempotencyUtils.buildKey("TTS", message.documentId(), message.pageNo(), message.textHash());
        Optional<StageTaskEntity> existingTask = stageTaskRepository.findByIdempotencyKey(idempotencyKey);
        if (existingTask.isPresent() && "COMPLETED".equalsIgnoreCase(existingTask.get().getStatus())) {
            log.info("StageTask with key {} already COMPLETED, skipping duplicate TTS call", idempotencyKey);
            checkAndTriggerAssembly(message.documentId());
            return;
        }

        // Status check: if already AUDIO_DONE or INDEXED, check document assembly
        if ("AUDIO_DONE".equalsIgnoreCase(page.getStatus()) || "INDEXED".equalsIgnoreCase(page.getStatus())) {
            log.info("Page {} is already in status {}, skipping duplicate TTS call", page.getId(), page.getStatus());
            checkAndTriggerAssembly(message.documentId());
            return;
        }

        try {
            // 1. Generate English translation audio
            String translatedText = page.getTranslatedText();
            if (translatedText != null && !translatedText.isBlank()) {
                generateAndSaveAudio(
                        translatedText,
                        "en-IN",
                        message.documentId(),
                        message.pageNo(),
                        "en"
                );
            }

            // 2. Generate original Indic audio if supported
            String origLanguage = normalizeLanguageCode(message.languageCode());
            if (isLanguageSupported(origLanguage)) {
                String originalText = page.getOriginalText();
                if (originalText != null && !originalText.isBlank()) {
                    generateAndSaveAudio(
                            originalText,
                            origLanguage,
                            message.documentId(),
                            message.pageNo(),
                            "orig"
                    );
                }
            } else {
                log.info("Language {} is not in Bulbul 11 supported languages, skipping original audio generation", message.languageCode());
            }

            // 3. Update page state
            page.setStatus("AUDIO_DONE");
            pageRepository.save(page);

            // 4. Record stage task idempotency
            StageTaskEntity stageTask = new StageTaskEntity(
                    UUID.randomUUID(),
                    page,
                    "TTS",
                    idempotencyKey,
                    "COMPLETED"
            );
            stageTaskRepository.save(stageTask);

            log.info("Page {} audio synthesis completed", page.getId());
            eventService.emitProgress(message.documentId(), message.pageNo(), "TTS", "COMPLETED", 
                    "Voice readout synthesized for page " + message.pageNo());

            // 5. Trigger assembly check
            checkAndTriggerAssembly(message.documentId());

        } catch (Exception e) {
            log.error("Failed TTS generation for page {}", page.getId(), e);
            page.setStatus("FAILED");
            pageRepository.save(page);
            eventService.emitProgress(message.documentId(), message.pageNo(), "TTS", "FAILED", 
                    "Audio synthesis failed for page " + message.pageNo() + ": " + e.getMessage());
            throw new RuntimeException("Error synthesizing audio for page " + page.getId(), e);
        }
    }

    private void generateAndSaveAudio(String text, String languageCode, UUID documentId, int pageNo, String langSuffix) {
        String targetStorageKey = "documents/" + documentId + "/audio/page_" + pageNo + "_" + langSuffix + ".wav";
        String contentHash = com.chitthi.util.IdempotencyUtils.sha256Hex(text.trim() + ":" + languageCode.toLowerCase());
        String cacheStorageKey = "cache/tts/" + contentHash + ".wav";

        // FR13: Check TTS cache by text hash
        if (objectStorageService.fileExists(cacheStorageKey)) {
            try {
                byte[] cachedAudioBytes = objectStorageService.downloadFile(cacheStorageKey);
                if (cachedAudioBytes != null && cachedAudioBytes.length > 0) {
                    objectStorageService.uploadFile(targetStorageKey, cachedAudioBytes, "audio/wav");
                    log.info("TTS Cache HIT for cacheKey: {}. Reused {} bytes for page {} ({}) with 0 duplicate API calls",
                            cacheStorageKey, cachedAudioBytes.length, pageNo, langSuffix);
                    return;
                }
            } catch (Exception e) {
                log.warn("Failed to retrieve cached TTS audio from {}. Falling back to Sarvam Bulbul synthesis: {}",
                        cacheStorageKey, e.getMessage());
            }
        }

        // Cache MISS: Synthesize via Sarvam Bulbul
        List<String> chunks = textChunkingService.chunkText(text, MAX_TTS_CHARS_PER_CHUNK);
        List<byte[]> audioChunks = new ArrayList<>();

        for (String chunk : chunks) {
            long startTime = System.currentTimeMillis();
            TtsResponse response = sarvamClient.textToSpeech(List.of(chunk), languageCode);
            long latencyMs = System.currentTimeMillis() - startTime;

            if (response != null && response.audios() != null) {
                for (String base64Audio : response.audios()) {
                    if (base64Audio != null && !base64Audio.isBlank()) {
                        audioChunks.add(Base64.getDecoder().decode(base64Audio.trim()));
                    }
                }
            }

            // Record cost ledger
            BigDecimal charsCount = BigDecimal.valueOf(chunk.length());
            BigDecimal cost = charsCount.multiply(COST_PER_CHAR_INR);
            ApiCallEntity apiCall = new ApiCallEntity(
                    UUID.randomUUID(),
                    documentId,
                    "/text-to-speech",
                    charsCount,
                    "characters",
                    latencyMs,
                    200,
                    cost
            );
            apiCallRepository.save(apiCall);
        }

        if (!audioChunks.isEmpty()) {
            byte[] pageAudioBytes = audioStitcherService.concatenateWavFiles(audioChunks);
            objectStorageService.uploadFile(targetStorageKey, pageAudioBytes, "audio/wav");
            log.info("Saved page audio to {} (size = {} bytes)", targetStorageKey, pageAudioBytes.length);

            // Populate TTS cache by text hash
            try {
                objectStorageService.uploadFile(cacheStorageKey, pageAudioBytes, "audio/wav");
                log.info("Populated TTS cache with key: {} (size = {} bytes)", cacheStorageKey, pageAudioBytes.length);
            } catch (Exception e) {
                log.warn("Failed to populate TTS cache for key {}: {}", cacheStorageKey, e.getMessage());
            }
        }
    }

    private void checkAndTriggerAssembly(UUID documentId) {
        List<PageEntity> allPages = pageRepository.findByDocumentIdOrderByPageNoAsc(documentId);
        if (allPages.isEmpty()) {
            return;
        }

        boolean allDone = allPages.stream()
                .allMatch(p -> "AUDIO_DONE".equalsIgnoreCase(p.getStatus()) 
                            || "INDEXED".equalsIgnoreCase(p.getStatus()) 
                            || "FAILED".equalsIgnoreCase(p.getStatus()));

        if (allDone) {
            log.info("All pages for document {} reached terminal stage. Triggering assemble.queue", documentId);
            DocumentAssembleMessage assembleMessage = new DocumentAssembleMessage(documentId);
            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE_NAME, RabbitConfig.ASSEMBLE_ROUTING_KEY, assembleMessage);
        }
    }

    private boolean isLanguageSupported(String lang) {
        if (lang == null) return false;
        return BULBUL_SUPPORTED_LANGUAGES.contains(lang.toLowerCase());
    }

    private String normalizeLanguageCode(String code) {
        if (code == null) return "en-IN";
        String lower = code.toLowerCase().trim();
        if (lower.contains("-")) {
            String[] parts = lower.split("-");
            return parts[0] + "-" + parts[1].toUpperCase();
        }
        return switch (lower) {
            case "hi" -> "hi-IN";
            case "bn" -> "bn-IN";
            case "gu" -> "gu-IN";
            case "kn" -> "kn-IN";
            case "ml" -> "ml-IN";
            case "mr" -> "mr-IN";
            case "od" -> "od-IN";
            case "pa" -> "pa-IN";
            case "ta" -> "ta-IN";
            case "te" -> "te-IN";
            case "en" -> "en-IN";
            default -> lower;
        };
    }
}
