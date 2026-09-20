package com.example.batterylab.repo;

import com.example.batterylab.domain.PointAmendment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PointAmendmentRepository extends JpaRepository<PointAmendment, Long> {
    Optional<PointAmendment> findByRunIdAndSeq(long runId, long seq);
}
