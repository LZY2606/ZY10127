package com.example.batterylab.domain;

import jakarta.persistence.*;

/**
 * Immutable evidence of one step execution window. Entered when the previous step left;
 * the exit sample is the first CONFIRMED sample proving the threshold/duration condition.
 * Because exits are only published from confirmed samples they can never go backwards.
 */
@Entity
@Table(name = "step_exec",
        indexes = @Index(name = "ix_step_run", columnList = "runId,enterSeq"))
public class StepExec {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long runId;

    @Column(nullable = false)
    private int stepIndex;

    @Column(nullable = false)
    private int cycleNo;

    @Column(nullable = false)
    private long enterTsMs;
    @Column(nullable = false)
    private long enterSeq;

    private Long exitTsMs;
    private Long exitSeq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StepType stepType;

    /** What made the step leave (NEXT, LOOP, TERMINAL, PAUSED ...). */
    @Column
    private String exitReason;

    protected StepExec() {
    }

    public StepExec(long runId, int stepIndex, int cycleNo, long enterTsMs, long enterSeq,
                    StepType stepType) {
        this.runId = runId;
        this.stepIndex = stepIndex;
        this.cycleNo = cycleNo;
        this.enterTsMs = enterTsMs;
        this.enterSeq = enterSeq;
        this.stepType = stepType;
    }

    public Long getId() {
        return id;
    }

    public long getRunId() {
        return runId;
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public int getCycleNo() {
        return cycleNo;
    }

    public long getEnterTsMs() {
        return enterTsMs;
    }

    public long getEnterSeq() {
        return enterSeq;
    }

    public Long getExitTsMs() {
        return exitTsMs;
    }

    public void setExitTsMs(Long exitTsMs) {
        this.exitTsMs = exitTsMs;
    }

    public Long getExitSeq() {
        return exitSeq;
    }

    public void setExitSeq(Long exitSeq) {
        this.exitSeq = exitSeq;
    }

    public StepType getStepType() {
        return stepType;
    }

    public String getExitReason() {
        return exitReason;
    }

    public void setExitReason(String exitReason) {
        this.exitReason = exitReason;
    }
}
