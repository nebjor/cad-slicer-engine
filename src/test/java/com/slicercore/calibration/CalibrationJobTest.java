package com.slicercore.calibration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A pass works through everything waiting, oldest first; a failing model stops none; passes never overlap. */
class CalibrationJobTest {

    private final CalibrationQueue queue = mock(CalibrationQueue.class);
    private final CalibrationRunner runner = mock(CalibrationRunner.class);
    private final CalibrationQueueEntry first = entry("first.stl");
    private final CalibrationQueueEntry second = entry("second.stl");
    private final CalibrationJob job = new CalibrationJob(queue, runner);

    @AfterEach
    void stop() {
        job.shutdown();
    }

    private static CalibrationQueueEntry entry(String name) {
        return CalibrationQueueEntry.queued("a".repeat(64), name, "k.stl", CalibrationSource.MANUAL, null,
                Instant.parse("2026-10-21T12:00:00Z"));
    }

    @Test
    void aPassTakesEverythingWaitingAndSurvivesAFailingModel() {
        when(queue.nextBatch(CalibrationJob.MAX_MODELS_PER_PASS)).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("disk full")).when(runner).calibrate(first);

        assertThat(job.runPass()).as("models worked through, the failed one excluded").isEqualTo(1);

        verify(runner).calibrate(first);
        verify(runner).calibrate(second);
    }

    @Test
    void runNowStartsOnePassInTheBackgroundAndRefusesASecondWhileItRuns() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(queue.nextBatch(anyInt())).thenReturn(List.of(first));
        doAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return 11;
        }).when(runner).calibrate(first);

        assertThat(job.runPassAsync()).isTrue();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(job.isRunning()).isTrue();
        assertThat(job.runPassAsync()).as("a second pass while one runs").isFalse();
        assertThat(job.runPass()).as("a synchronous pass while one runs").isZero();

        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (job.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(job.isRunning()).isFalse();
        verify(runner).calibrate(first);
        verify(runner, never()).calibrate(second);
        assertThat(job.runPassAsync()).as("free again").isTrue();
    }

    @Test
    void anEmptyQueueIsANoOp() {
        when(queue.nextBatch(anyInt())).thenReturn(List.of());

        assertThat(job.runPass()).isZero();
        verify(runner, never()).calibrate(any());
    }
}
