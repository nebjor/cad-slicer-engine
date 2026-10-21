package com.slicercore.calibration;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CalibrationQueueRepository extends JpaRepository<CalibrationQueueEntry, UUID> {

    /** What is waiting, oldest first. */
    List<CalibrationQueueEntry> findAllByStartedAtIsNullOrderByQueuedAtAsc(Pageable page);

    /** Everything, newest first, for the admin view. */
    List<CalibrationQueueEntry> findAllByOrderByQueuedAtDesc(Pageable page);

    long countByStartedAtIsNull();
}
