package com.example.batterylab.repo;

import com.example.batterylab.domain.ProtocolVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProtocolVersionRepository extends JpaRepository<ProtocolVersion, Long> {
    Optional<ProtocolVersion> findByNameAndVersion(String name, String version);
    List<ProtocolVersion> findAllByOrderByIdAsc();
}
