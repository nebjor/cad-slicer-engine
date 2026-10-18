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
    void releasesTheSlotWhenTheTaskFails() {
        SlicerCapacityGate gate = new SlicerCapacityGate(new SlicerCapacityProperties(2, 0));

        assertThatThrownBy(() -> gate.run(() -> {
            throw new IllegalStateException("slicer crashed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(gate.availableSlots()).isEqualTo(2);
    }
}
