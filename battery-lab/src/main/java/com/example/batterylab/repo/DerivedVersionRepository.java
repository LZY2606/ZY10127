package com.example.batterylab.repo;

import com.example.batterylab.domain.DerivedVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DerivedVersionRepository extends JpaRepository<DerivedVersion, Long> {
    List<DerivedVersion> findByRunIdOrderByVersionNoAsc(long runId);
    Optional<DerivedVersion> findByRunIdAndVersionNo(long runId, int versionNo);
}
