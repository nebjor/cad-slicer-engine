package com.slicercore.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the retention sweep once a night; the cron is configurable for tests and operations. */
@Component
public class OrderRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(OrderRetentionJob.class);

    private final OrderRetentionService retention;

    public OrderRetentionJob(OrderRetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(cron = "${orders.retention-cron:0 30 3 * * *}")
    public void run() {
        int erased = retention.eraseExpired();
        int abandoned = retention.eraseAbandoned();
        if (erased > 0 || abandoned > 0) {
            log.info("Retention sweep erased {} shipped and {} abandoned order(s)", erased, abandoned);
        }
    }
}
