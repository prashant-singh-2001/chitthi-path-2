package com.chitthi.service;

import com.chitthi.config.RabbitConfig;
import com.chitthi.domain.entity.OutboxEntity;
import com.chitthi.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@Service
public class OutboxPublisherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;

    public OutboxPublisherService(OutboxRepository outboxRepository, RabbitTemplate rabbitTemplate) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Polls unpublished outbox records and dispatches them to RabbitMQ.
     * Runs every 2 seconds by default to guarantee eventual delivery.
     */
    @Scheduled(fixedDelayString = "${chitthi.outbox.poll-interval-ms:2000}")
    @Transactional
    public int publishUnpublishedRecords() {
        List<OutboxEntity> pendingRecords = outboxRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc();
        if (pendingRecords.isEmpty()) {
            return 0;
        }

        log.debug("Found {} pending outbox records to publish", pendingRecords.size());
        int publishedCount = 0;

        for (OutboxEntity outbox : pendingRecords) {
            try {
                Message message = MessageBuilder
                        .withBody(outbox.getPayload().getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .build();

                rabbitTemplate.send(RabbitConfig.EXCHANGE_NAME, outbox.getTopic(), message);
                outbox.setPublishedAt(Instant.now());
                publishedCount++;
                log.info("Published outbox record {} to topic {}", outbox.getId(), outbox.getTopic());
            } catch (Exception e) {
                log.error("Failed to publish outbox record {} to topic {}: {}", outbox.getId(), outbox.getTopic(), e.getMessage());
                // Break to avoid thrashing RabbitMQ if broker is down; will retry next interval
                break;
            }
        }

        if (publishedCount > 0) {
            outboxRepository.saveAll(pendingRecords.subList(0, publishedCount));
            log.info("Successfully published and updated {} outbox records", publishedCount);
        }

        return publishedCount;
    }
}
