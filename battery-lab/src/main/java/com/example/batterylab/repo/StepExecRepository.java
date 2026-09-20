package com.example.batterylab.repo;

import com.example.batterylab.domain.StepExec;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StepExecRepository extends JpaRepository<StepExec, Long> {
    List<StepExec> findByRunIdOrderByIdAsc(long runId);
}
