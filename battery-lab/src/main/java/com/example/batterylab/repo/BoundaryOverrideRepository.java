package com.example.batterylab.repo;

import com.example.batterylab.domain.BoundaryOverride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BoundaryOverrideRepository extends JpaRepository<BoundaryOverride, Long> {
    List<BoundaryOverride> findByRunIdOrderByCycleNoAsc(long runId);
    Optional<BoundaryOverride> findByRunIdAndCycleNo(long runId, int cycleNo);
}
