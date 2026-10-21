package com.slicercore.order;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** One nightly run erases orders and then lets the other modules sweep; one failing sweep stops none. */
class OrderRetentionJobTest {

    @Test
    void runsTheOrderErasureAndEverySweepEvenWhenOneFails() {
        OrderRetentionService retention = mock(OrderRetentionService.class);
        when(retention.eraseExpired()).thenReturn(1);
        when(retention.eraseAbandoned()).thenReturn(0);
        AtomicInteger swept = new AtomicInteger();
        RetentionSweep failing = sweep("broken things", () -> {
            throw new IllegalStateException("table locked");
        });
        RetentionSweep counting = sweep("expired things", () -> swept.incrementAndGet());

        new OrderRetentionJob(retention, List.of(failing, counting)).run();

        verify(retention).eraseExpired();
        verify(retention).eraseAbandoned();
        assertThat(swept.get()).isEqualTo(1);
    }

    private static RetentionSweep sweep(String subject, java.util.function.IntSupplier body) {
        return new RetentionSweep() {
            @Override
            public String subject() {
                return subject;
            }

            @Override
            public int sweep() {
                return body.getAsInt();
            }
        };
    }
}
