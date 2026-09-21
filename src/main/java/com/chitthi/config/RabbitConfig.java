package com.chitthi.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE_NAME = "chitthi.direct";
    public static final String DEAD_LETTER_EXCHANGE = "chitthi.dlx";

    public static final String OCR_QUEUE = "ocr.queue";
    public static final String TRANSLATE_QUEUE = "translate.queue";
    public static final String TTS_QUEUE = "tts.queue";
    public static final String ASSEMBLE_QUEUE = "assemble.queue";
    public static final String DEAD_LETTER_QUEUE = "chitthi.dlq";

    public static final String OCR_ROUTING_KEY = "ocr.route";
    public static final String TRANSLATE_ROUTING_KEY = "translate.route";
    public static final String TTS_ROUTING_KEY = "tts.route";
    public static final String ASSEMBLE_ROUTING_KEY = "assemble.route";
    public static final String DLQ_ROUTING_KEY = "dead.letter.route";

    @Bean
    public DirectExchange directExchange() {
        return new DirectExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(DLQ_ROUTING_KEY);
    }

    private Map<String, Object> standardQueueArgs() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE);
        args.put("x-dead-letter-routing-key", DLQ_ROUTING_KEY);
        return args;
    }

    @Bean
    public Queue ocrQueue() {
        return new Queue(OCR_QUEUE, true, false, false, standardQueueArgs());
    }

    @Bean
    public Queue translateQueue() {
        return new Queue(TRANSLATE_QUEUE, true, false, false, standardQueueArgs());
    }

    @Bean
    public Queue ttsQueue() {
        return new Queue(TTS_QUEUE, true, false, false, standardQueueArgs());
    }

    @Bean
    public Queue assembleQueue() {
        return new Queue(ASSEMBLE_QUEUE, true, false, false, standardQueueArgs());
    }

    @Bean
    public Binding ocrBinding(Queue ocrQueue, DirectExchange directExchange) {
        return BindingBuilder.bind(ocrQueue).to(directExchange).with(OCR_ROUTING_KEY);
    }

    @Bean
    public Binding translateBinding(Queue translateQueue, DirectExchange directExchange) {
        return BindingBuilder.bind(translateQueue).to(directExchange).with(TRANSLATE_ROUTING_KEY);
    }

    @Bean
    public Binding ttsBinding(Queue ttsQueue, DirectExchange directExchange) {
        return BindingBuilder.bind(ttsQueue).to(directExchange).with(TTS_ROUTING_KEY);
    }

    @Bean
    public Binding assembleBinding(Queue assembleQueue, DirectExchange directExchange) {
        return BindingBuilder.bind(assembleQueue).to(directExchange).with(ASSEMBLE_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}
