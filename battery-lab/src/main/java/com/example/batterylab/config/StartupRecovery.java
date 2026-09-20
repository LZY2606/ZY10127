package com.example.batterylab.config;

import com.example.batterylab.domain.InterruptReason;
import com.example.batterylab.domain.Run;
import com.example.batterylab.domain.RunStatus;
import com.example.batterylab.repo.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Recovers runs after a process restart.
 *
 * A RUNNING row means the process disappeared mid-execution: the virtual clock cannot be
 * trusted to have stopped cleanly at a sample, so the run is parked to PAUSED and must be
 * explicitly resumed. Terminal rows stay terminal forever. CREATED runs were never started.
 */
@Component
@Order(20)
public class StartupRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    private final RunRepository runRepository;

    public StartupRecovery(RunRepository runRepository) {
        this.runRepository = runRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Run> runs = runRepository.findAll();
        for (Run run : runs) {
            if (run.getStatus() == RunStatus.RUNNING) {
                run.setStatus(RunStatus.PAUSED);
                run.setInterruptReason(InterruptReason.PAUSED);
                runRepository.save(run);
                log.warn("run {} was RUNNING at restart -> parked to PAUSED (needs resume)",
                        run.getId());
            }
        }
    }
}
