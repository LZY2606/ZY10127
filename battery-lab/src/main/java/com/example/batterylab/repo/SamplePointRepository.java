package com.example.batterylab.repo;

import com.example.batterylab.domain.SamplePoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SamplePointRepository extends JpaRepository<SamplePoint, Long> {
    List<SamplePoint> findByRunIdOrderByTsMsAscSeqAsc(long runId);
    List<SamplePoint> findByRunIdOrderBySeqAsc(long runId);
    Optional<SamplePoint> findByRunIdAndSeq(long runId, long seq);
    long countByRunId(long runId);
}
