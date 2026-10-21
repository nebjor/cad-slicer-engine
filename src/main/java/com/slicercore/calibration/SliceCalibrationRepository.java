package com.slicercore.calibration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SliceCalibrationRepository extends JpaRepository<SliceCalibration, UUID> {

    /** Every row, oldest first, so a later repeat of a cell can replace an earlier one. */
    List<SliceCalibration> findAllByOrderByMeasuredAtAsc();
}
