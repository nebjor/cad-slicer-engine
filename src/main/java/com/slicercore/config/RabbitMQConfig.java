package com.slicercore.config;

import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Queue topology for asynchronous slicing jobs (roadmap phase 6). Only created when
 * {@code app.messaging.enabled=true}, so the service boots without a broker.
 */
@Configuration
@ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true")
public class RabbitMQConfig {

    public static final String QUEUE_NAME = "slicing.jobs.queue";
    public static final String EXCHANGE_NAME = "slicing.exchange";
    public static final String ROUTING_KEY = "slicing.job.created";

    @Bean
    public Queue slicingQueue() {
        // Durable queue persists through RabbitMQ container restarts
        return QueueBuilder.durable(QUEUE_NAME).build();
    }

    @Bean
    public DirectExchange slicingExchange() {
        return new DirectExchange(EXCHANGE_NAME);
    }

    @Bean
    public Binding binding(Queue slicingQueue, DirectExchange slicingExchange) {
        return BindingBuilder.bind(slicingQueue).to(slicingExchange).with(ROUTING_KEY);
    }
}