package com.example.batterylab.repo;

import com.example.batterylab.domain.CycleResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CycleResultRepository extends JpaRepository<CycleResult, Long> {
    List<CycleResult> findByDerivedVersionIdOrderByCycleNoAsc(long derivedVersionId);
}
