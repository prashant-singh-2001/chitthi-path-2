package com.chitthi.service;

import com.chitthi.config.RabbitConfig;
import com.chitthi.domain.entity.OutboxEntity;
import com.chitthi.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherServiceTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private OutboxPublisherService outboxPublisherService;

    @BeforeEach
    void setUp() {
        outboxPublisherService = new OutboxPublisherService(outboxRepository, rabbitTemplate);
    }

    @Test
    void publishUnpublishedRecords_whenNoPendingRecords_shouldReturnZero() {
        when(outboxRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(Collections.emptyList());

        int count = outboxPublisherService.publishUnpublishedRecords();

        assertThat(count).isEqualTo(0);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void publishUnpublishedRecords_whenRecordsPending_shouldPublishAndUpdateTimestamp() {
        OutboxEntity outbox1 = new OutboxEntity(UUID.randomUUID(), RabbitConfig.OCR_ROUTING_KEY, "{\"batchId\":\"b1\"}");
        OutboxEntity outbox2 = new OutboxEntity(UUID.randomUUID(), RabbitConfig.TRANSLATE_ROUTING_KEY, "{\"pageId\":\"p1\"}");

        when(outboxRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(outbox1, outbox2));

        int count = outboxPublisherService.publishUnpublishedRecords();

        assertThat(count).isEqualTo(2);
        assertThat(outbox1.getPublishedAt()).isNotNull();
        assertThat(outbox2.getPublishedAt()).isNotNull();

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(RabbitConfig.EXCHANGE_NAME), eq(RabbitConfig.OCR_ROUTING_KEY), messageCaptor.capture());
        verify(rabbitTemplate).send(eq(RabbitConfig.EXCHANGE_NAME), eq(RabbitConfig.TRANSLATE_ROUTING_KEY), messageCaptor.capture());

        verify(outboxRepository).saveAll(anyList());
    }

    @Test
    void publishUnpublishedRecords_whenRabbitFails_shouldStopAndNotUpdateFailedRecord() {
        OutboxEntity outbox1 = new OutboxEntity(UUID.randomUUID(), RabbitConfig.OCR_ROUTING_KEY, "{\"batchId\":\"b1\"}");
        OutboxEntity outbox2 = new OutboxEntity(UUID.randomUUID(), RabbitConfig.TRANSLATE_ROUTING_KEY, "{\"pageId\":\"p1\"}");

        when(outboxRepository.findTop50ByPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(outbox1, outbox2));

        doThrow(new RuntimeException("Rabbit broker down"))
                .when(rabbitTemplate).send(eq(RabbitConfig.EXCHANGE_NAME), eq(RabbitConfig.OCR_ROUTING_KEY), any(Message.class));

        int count = outboxPublisherService.publishUnpublishedRecords();

        assertThat(count).isEqualTo(0);
        assertThat(outbox1.getPublishedAt()).isNull();
        assertThat(outbox2.getPublishedAt()).isNull();
        verify(outboxRepository, never()).saveAll(anyList());
    }
}
