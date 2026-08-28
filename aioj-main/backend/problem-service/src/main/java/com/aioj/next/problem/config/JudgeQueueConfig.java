package com.aioj.next.problem.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class JudgeQueueConfig {
    public static final String JUDGE_EXCHANGE = "aioj.judge.exchange";
    public static final String JUDGE_QUEUE = "aioj.judge.queue";
    public static final String JUDGE_DEAD_LETTER_QUEUE = "aioj.judge.dlq";
    public static final String JUDGE_ROUTING_KEY = "judge.submit";

    @Bean
    DirectExchange judgeExchange() {
        return new DirectExchange(JUDGE_EXCHANGE, true, false);
    }

    @Bean
    Queue judgeQueue() {
        return new Queue(JUDGE_QUEUE, true, false, false, Map.of(
                "x-dead-letter-exchange", JUDGE_EXCHANGE,
                "x-dead-letter-routing-key", "judge.dead"
        ));
    }

    @Bean
    Queue judgeDeadLetterQueue() {
        return new Queue(JUDGE_DEAD_LETTER_QUEUE, true);
    }

    @Bean
    Binding judgeBinding(Queue judgeQueue, DirectExchange judgeExchange) {
        return BindingBuilder.bind(judgeQueue).to(judgeExchange).with(JUDGE_ROUTING_KEY);
    }

    @Bean
    Binding judgeDeadLetterBinding(Queue judgeDeadLetterQueue, DirectExchange judgeExchange) {
        return BindingBuilder.bind(judgeDeadLetterQueue).to(judgeExchange).with("judge.dead");
    }

    @Bean
    Jackson2JsonMessageConverter jackson2JsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    RabbitTemplate rabbitTemplate(org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory,
                                  Jackson2JsonMessageConverter converter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(converter);
        return rabbitTemplate;
    }
}
