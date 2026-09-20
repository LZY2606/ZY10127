package com.example.batterylab.repo;

import com.example.batterylab.domain.PointExclusion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PointExclusionRepository extends JpaRepository<PointExclusion, Long> {
    List<PointExclusion> findByRunIdOrderByFromTsMsAsc(long runId);
}
