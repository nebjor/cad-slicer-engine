package com.slicercore.service;

import com.slicercore.config.SlicerCapacityProperties;
import com.slicercore.model.SlicerBusyException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlicerCapacityGateTest {

    @Test
    void runsTaskAndReleasesTheSlot() {
        SlicerCapacityGate gate = new SlicerCapacityGate(new SlicerCapacityProperties(1, 0));

        assertThat(gate.run(() -> "sliced")).isEqualTo("sliced");
        assertThat(gate.availableSlots()).isEqualTo(1);
    }

    @Test
    void rejectsWhenNoSlotFreesUpInTime() {
        SlicerCapacityGate gate = new SlicerCapacityGate(new SlicerCapacityProperties(1, 0));

        // The outer task holds the only slot; the nested call cannot get one and must not wait forever.
        assertThatThrownBy(() -> gate.run(() -> gate.run(() -> "never")))
                .isInstanceOf(SlicerBusyException.class)
                .hasMessageContaining("busy");
        assertThat(gate.availableSlots()).isEqualTo(1);
    }

    @Test
    void aBackgroundTaskWaitsAsLongAsItIsToldForASlot() throws InterruptedException {
        SlicerCapacityGate gate = new SlicerCapacityGate(new SlicerCapacityProperties(1, 0));
        Thread holder = Thread.ofVirtual().start(() -> gate.run(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "quote";
        }));
        Thread.sleep(50);

        // The configured wait of zero would reject at once; the longer wait outlasts the quote.
        assertThatThrownBy(() -> gate.run(() -> "never")).isInstanceOf(SlicerBusyException.class);
        assertThat(gate.run(() -> "calibrated", 5)).isEqualTo("calibrated");
        holder.join();
        assertThat(gate.availableSlots()).isEqualTo(1);
    }

    @Test
    void releasesTheSlotWhenTheTaskFails() {
        SlicerCapacityGate gate = new SlicerCapacityGate(new SlicerCapacityProperties(2, 0));

        assertThatThrownBy(() -> gate.run(() -> {
            throw new IllegalStateException("slicer crashed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(gate.availableSlots()).isEqualTo(2);
    }
}
