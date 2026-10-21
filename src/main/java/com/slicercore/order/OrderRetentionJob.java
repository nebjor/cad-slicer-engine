package com.slicercore.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Runs the retention sweep once a night; the cron is configurable for tests and operations.
 * Other modules join the same run through {@link RetentionSweep}, so there is one nightly
 * sweep to reason about, not one per table.
 */
@Component
public class OrderRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(OrderRetentionJob.class);

    private final OrderRetentionService retention;
    private final List<RetentionSweep> sweeps;

    public OrderRetentionJob(OrderRetentionService retention, List<RetentionSweep> sweeps) {
        this.retention = retention;
        this.sweeps = sweeps;
    }

    @Scheduled(cron = "${orders.retention-cron:0 30 3 * * *}")
    public void run() {
        int erased = retention.eraseExpired();
        int abandoned = retention.eraseAbandoned();
        if (erased > 0 || abandoned > 0) {
            log.info("Retention sweep erased {} shipped and {} abandoned order(s)", erased, abandoned);
        }
        for (RetentionSweep sweep : sweeps) {
            try {
                int removed = sweep.sweep();
                if (removed > 0) {
                    log.info("Retention sweep removed {} {}", removed, sweep.subject());
                }
            } catch (RuntimeException e) {
                log.error("Retention sweep of {} failed: {}", sweep.subject(), e.getMessage(), e);
            }
        }
    }
}
