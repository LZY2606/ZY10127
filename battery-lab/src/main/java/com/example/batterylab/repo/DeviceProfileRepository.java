package com.example.batterylab.repo;

import com.example.batterylab.domain.DeviceProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceProfileRepository extends JpaRepository<DeviceProfile, Long> {
    Optional<DeviceProfile> findByNameAndVersion(String name, String version);
    List<DeviceProfile> findAllByOrderByIdAsc();
}
