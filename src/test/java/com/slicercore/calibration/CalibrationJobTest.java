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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** One pass works through the oldest queued models; a failing model stops none; passes never overlap. */
class CalibrationJobTest {

    private final CalibrationQueue queue = mock(CalibrationQueue.class);
    private final CalibrationRunner runner = mock(CalibrationRunner.class);
    private final CalibrationQueueEntry first = entry("first.stl");
    private final CalibrationQueueEntry second = entry("second.stl");
    private CalibrationJob job;

    @AfterEach
    void stop() {
        if (job != null) {
            job.shutdown();
        }
    }

    private CalibrationJob job(boolean enabled, int maxPerNight) {
        job = new CalibrationJob(new CalibrationProperties(enabled, maxPerNight, "unused"), queue, runner);
        return job;
    }

    private static CalibrationQueueEntry entry(String name) {
        return CalibrationQueueEntry.queued("a".repeat(64), name, "k.stl", CalibrationSource.MANUAL, null,
                Instant.parse("2026-10-21T12:00:00Z"));
    }

    @Test
    void theNightlyPassTakesTheConfiguredNumberOfModelsAndSurvivesAFailingOne() {
        when(queue.nextBatch(2)).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("disk full")).when(runner).calibrate(first);

        job(true, 2).nightly();

        verify(runner).calibrate(first);
        verify(runner).calibrate(second);
    }

    @Test
    void runPassReportsHowManyModelsWentThrough() {
        when(queue.nextBatch(5)).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("disk full")).when(runner).calibrate(first);

        assertThat(job(true, 5).runPass()).isEqualTo(1);
    }

    @Test
    void aDisabledCalibrationSkipsTheNightButStillRunsOnRequest() {
        when(queue.nextBatch(anyInt())).thenReturn(List.of(first));

        job(false, 5).nightly();
        verifyNoInteractions(runner);

        assertThat(job.runPass()).isEqualTo(1);
        verify(runner).calibrate(first);
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
        job(true, 5);

        assertThat(job.runPassAsync()).isTrue();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(job.isRunning()).isTrue();
        assertThat(job.runPassAsync()).as("a second pass while one runs").isFalse();
        assertThat(job.runPass()).as("the scheduled pass while one runs").isZero();

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

        assertThat(job(true, 5).runPass()).isZero();
        verify(runner, never()).calibrate(any());
    }
}
