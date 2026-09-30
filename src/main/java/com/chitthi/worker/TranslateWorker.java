package com.chitthi.worker;

import com.chitthi.client.sarvam.SarvamClient;
import com.chitthi.client.sarvam.dto.TranslateRequest;
import com.chitthi.client.sarvam.dto.TranslateResponse;
import com.chitthi.config.RabbitConfig;
import com.chitthi.domain.entity.ApiCallEntity;
import com.chitthi.domain.entity.PageEntity;
import com.chitthi.domain.entity.StageTaskEntity;
import com.chitthi.messaging.dto.PageTtsMessage;
import com.chitthi.messaging.dto.PageTranslateMessage;
import com.chitthi.repository.ApiCallRepository;
import com.chitthi.repository.PageRepository;
import com.chitthi.repository.StageTaskRepository;
import com.chitthi.service.DocumentProgressEventService;
import com.chitthi.service.TextChunkingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class TranslateWorker {

    private static final Logger log = LoggerFactory.getLogger(TranslateWorker.class);
    private static final int MAX_TRANSLATE_CHARS_PER_CHUNK = 2000;
    private static final BigDecimal COST_PER_CHAR_INR = new BigDecimal("0.0010"); // ₹10 per 10k chars

    private final PageRepository pageRepository;
    private final StageTaskRepository stageTaskRepository;
    private final ApiCallRepository apiCallRepository;
    private final SarvamClient sarvamClient;
    private final TextChunkingService textChunkingService;
    private final RabbitTemplate rabbitTemplate;
    private final DocumentProgressEventService eventService;

    public TranslateWorker(PageRepository pageRepository,
                           StageTaskRepository stageTaskRepository,
                           ApiCallRepository apiCallRepository,
                           SarvamClient sarvamClient,
                           TextChunkingService textChunkingService,
                           RabbitTemplate rabbitTemplate,
                           DocumentProgressEventService eventService) {
        this.pageRepository = pageRepository;
        this.stageTaskRepository = stageTaskRepository;
        this.apiCallRepository = apiCallRepository;
        this.sarvamClient = sarvamClient;
        this.textChunkingService = textChunkingService;
        this.rabbitTemplate = rabbitTemplate;
        this.eventService = eventService;
    }

    @RabbitListener(queues = RabbitConfig.TRANSLATE_QUEUE)
    @Transactional
    public void processPageTranslate(PageTranslateMessage message) {
        log.info("Received translate task for pageId: {}, docId: {}, pageNo: {}",
                message.pageId(), message.documentId(), message.pageNo());

        Optional<PageEntity> pageOpt = pageRepository.findById(message.pageId());
        if (pageOpt.isEmpty()) {
            log.warn("Page {} not found in database, skipping", message.pageId());
            return;
        }

        PageEntity page = pageOpt.get();

        String idempotencyKey = com.chitthi.util.IdempotencyUtils.buildKey("TRANSLATE", message.documentId(), message.pageNo(), message.textHash());
        Optional<StageTaskEntity> existingTask = stageTaskRepository.findByIdempotencyKey(idempotencyKey);
        if (existingTask.isPresent() && "COMPLETED".equalsIgnoreCase(existingTask.get().getStatus())) {
            log.info("StageTask with key {} already COMPLETED, skipping duplicate translation call", idempotencyKey);
            dispatchTts(message);
            return;
        }

        // Status check: if page already translated, skip paid API call
        if ("TRANSLATED".equalsIgnoreCase(page.getStatus()) || 
            "AUDIO_DONE".equalsIgnoreCase(page.getStatus()) || 
            "INDEXED".equalsIgnoreCase(page.getStatus())) {
            log.info("Page {} already in status {}, skipping duplicate translation call", page.getId(), page.getStatus());
            dispatchTts(message);
            return;
        }

        String originalText = page.getOriginalText();
        if (originalText == null || originalText.isBlank()) {
            log.info("Page {} has no text, advancing to TRANSLATED with empty string", page.getId());
            page.setTranslatedText("");
            page.setStatus("TRANSLATED");
            pageRepository.save(page);
            dispatchTts(message);
            return;
        }

        try {
            List<String> chunks = textChunkingService.chunkText(originalText, MAX_TRANSLATE_CHARS_PER_CHUNK);
            StringBuilder translatedResult = new StringBuilder();

            for (String chunk : chunks) {
                long startTime = System.currentTimeMillis();
                TranslateRequest request = TranslateRequest.toEnglish(chunk, message.languageCode());
                TranslateResponse response = sarvamClient.translate(request);
                long latencyMs = System.currentTimeMillis() - startTime;

                if (response != null && response.translatedText() != null) {
                    if (translatedResult.length() > 0) {
                        translatedResult.append(" ");
                    }
                    translatedResult.append(response.translatedText().trim());
                }

                // Record cost ledger
                BigDecimal charsCount = BigDecimal.valueOf(chunk.length());
                BigDecimal cost = charsCount.multiply(COST_PER_CHAR_INR);
                ApiCallEntity apiCall = new ApiCallEntity(
                        UUID.randomUUID(),
                        message.documentId(),
                        "/translate",
                        charsCount,
                        "characters",
                        latencyMs,
                        200,
                        cost
                );
                apiCallRepository.save(apiCall);
            }

            String fullTranslatedText = translatedResult.toString().trim();
            page.setTranslatedText(fullTranslatedText);
            page.setStatus("TRANSLATED");
            pageRepository.save(page);

            // Record stage task idempotency
            StageTaskEntity stageTask = new StageTaskEntity(
                    UUID.randomUUID(),
                    page,
                    "TRANSLATE",
                    idempotencyKey,
                    "COMPLETED"
            );
            stageTaskRepository.save(stageTask);

            log.info("Page {} translated successfully (translated chars: {})", page.getId(), fullTranslatedText.length());
            eventService.emitProgress(message.documentId(), message.pageNo(), "TRANSLATE", "COMPLETED", 
                    "Page " + message.pageNo() + " translated to English");
            dispatchTts(message);

        } catch (Exception e) {
            log.error("Failed to translate page {}", page.getId(), e);
            page.setStatus("FAILED");
            pageRepository.save(page);
            eventService.emitProgress(message.documentId(), message.pageNo(), "TRANSLATE", "FAILED", 
                    "Translation failed for page " + message.pageNo() + ": " + e.getMessage());
            throw new RuntimeException("Error translating page " + page.getId(), e);
        }
    }

    private void dispatchTts(PageTranslateMessage message) {
        PageTtsMessage ttsMessage = new PageTtsMessage(
                message.pageId(),
                message.documentId(),
                message.pageNo(),
                message.languageCode(),
                message.textHash()
        );
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE_NAME, RabbitConfig.TTS_ROUTING_KEY, ttsMessage);
        log.info("Dispatched TTS message for page {} of document {}", message.pageNo(), message.documentId());
    }
}
