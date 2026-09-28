package com.chitthi.worker;

import com.chitthi.config.RabbitConfig;
import com.chitthi.domain.entity.DocumentEntity;
import com.chitthi.domain.entity.PageEntity;
import com.chitthi.messaging.dto.DocumentAssembleMessage;
import com.chitthi.repository.DocumentRepository;
import com.chitthi.repository.PageRepository;
import com.chitthi.service.AudioStitcherService;
import com.chitthi.storage.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class AudioAssemblerWorker {

    private static final Logger log = LoggerFactory.getLogger(AudioAssemblerWorker.class);

    private final DocumentRepository documentRepository;
    private final PageRepository pageRepository;
    private final ObjectStorageService objectStorageService;
    private final AudioStitcherService audioStitcherService;

    public AudioAssemblerWorker(DocumentRepository documentRepository,
                                PageRepository pageRepository,
                                ObjectStorageService objectStorageService,
                                AudioStitcherService audioStitcherService) {
        this.documentRepository = documentRepository;
        this.pageRepository = pageRepository;
        this.objectStorageService = objectStorageService;
        this.audioStitcherService = audioStitcherService;
    }

    @RabbitListener(queues = RabbitConfig.ASSEMBLE_QUEUE)
    @Transactional
    public void processDocumentAssemble(DocumentAssembleMessage message) {
        log.info("Received assemble task for documentId: {}", message.documentId());

        Optional<DocumentEntity> docOpt = documentRepository.findById(message.documentId());
        if (docOpt.isEmpty()) {
            log.warn("Document {} not found, skipping assembly", message.documentId());
            return;
        }

        DocumentEntity document = docOpt.get();

        // Idempotency check: if already COMPLETE, skip duplicate audio assembly
        if ("COMPLETE".equalsIgnoreCase(document.getStatus())) {
            log.info("Document {} is already COMPLETE, skipping duplicate assembly", document.getId());
            return;
        }

        List<PageEntity> pages = pageRepository.findByDocumentIdOrderByPageNoAsc(message.documentId());
        if (pages.isEmpty()) {
            log.warn("No pages found for document {}, skipping", message.documentId());
            return;
        }

        try {
            // 1. Assemble English full track
            assembleLanguageTrack(message.documentId(), pages, "en");

            // 2. Assemble Original Indic full track
            assembleLanguageTrack(message.documentId(), pages, "orig");

            // 3. Update all pages to INDEXED
            for (PageEntity page : pages) {
                if ("AUDIO_DONE".equalsIgnoreCase(page.getStatus())) {
                    page.setStatus("INDEXED");
                }
            }
            pageRepository.saveAll(pages);

            // 4. Update Document status to COMPLETE
            document.setStatus("COMPLETE");
            documentRepository.save(document);

            log.info("Document {} assembled and marked COMPLETE with all pages INDEXED", document.getId());

        } catch (Exception e) {
            log.error("Failed to assemble audio for document {}", document.getId(), e);
            document.setStatus("PARTIAL");
            documentRepository.save(document);
            throw new RuntimeException("Error assembling document audio " + document.getId(), e);
        }
    }

    private void assembleLanguageTrack(java.util.UUID documentId, List<PageEntity> pages, String langSuffix) {
        List<byte[]> pageAudios = new ArrayList<>();

        for (PageEntity page : pages) {
            String pageKey = "documents/" + documentId + "/audio/page_" + page.getPageNo() + "_" + langSuffix + ".wav";
            try {
                byte[] audioBytes = objectStorageService.downloadFile(pageKey);
                if (audioBytes != null && audioBytes.length > 0) {
                    pageAudios.add(audioBytes);
                }
            } catch (Exception e) {
                log.debug("No audio found for key {} (might not have been generated for this language)", pageKey);
            }
        }

        if (!pageAudios.isEmpty()) {
            byte[] fullAudio = audioStitcherService.concatenateWavFiles(pageAudios);
            String fullKey = "documents/" + documentId + "/audio/full_" + langSuffix + ".wav";
            objectStorageService.uploadFile(fullKey, fullAudio, "audio/wav");
            log.info("Uploaded stitched full audio track to {} (pages = {}, total size = {} bytes)",
                    fullKey, pageAudios.size(), fullAudio.length);
        }
    }
}
