package com.slicercore.service;

import com.slicercore.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Consumes slicing jobs from the queue; inactive unless {@code app.messaging.enabled=true}. */
@Service
@ConditionalOnProperty(name = "app.messaging.enabled", havingValue = "true")
public class JobQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(JobQueueConsumer.class);
    private final SlicerExecutionService slicerExecutionService;

    public JobQueueConsumer(SlicerExecutionService slicerExecutionService) {
        this.slicerExecutionService = slicerExecutionService;
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_NAME)
    public void handleSlicingJob(String jobId) {
        log.info("Received new slicing job from RabbitMQ. Job ID: {}", jobId);

        try {
            // TODO: 1. Download STL file from MinIO
            // TODO: 2. Invoke slicerExecutionService
            // TODO: 3. Upload generated G-code to MinIO & update database state

            log.info("Job {} processed successfully!", jobId);
        } catch (Exception e) {
            log.error("Error processing job {}", jobId, e);
        }
    }
}