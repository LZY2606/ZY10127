package com.example.batterylab.repo;

import com.example.batterylab.domain.Run;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RunRepository extends JpaRepository<Run, Long> {
    List<Run> findAllByOrderByIdDesc();
}
