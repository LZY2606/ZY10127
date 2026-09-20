package com.example.batterylab.repo;

import com.example.batterylab.domain.ConflictStatus;
import com.example.batterylab.domain.SampleConflict;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SampleConflictRepository extends JpaRepository<SampleConflict, Long> {
    List<SampleConflict> findByRunIdAndStatusOrderByIdAsc(long runId, ConflictStatus status);
    List<SampleConflict> findByRunIdOrderByIdAsc(long runId);
    boolean existsByRunIdAndStatus(long runId, ConflictStatus status);
}
