package com.example.batterylab.repo;

import com.example.batterylab.domain.RuleVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RuleVersionRepository extends JpaRepository<RuleVersion, Long> {
    Optional<RuleVersion> findByNameAndVersion(String name, String version);
    List<RuleVersion> findAllByOrderByIdAsc();
}
