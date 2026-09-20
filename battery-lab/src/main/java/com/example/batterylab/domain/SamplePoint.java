package com.example.batterylab.domain;

import jakarta.persistence.*;

/**
 * A raw, append-only measurement point. Rows are never modified after confirmation;
 * a duplicate device sequence carrying different payload creates a {@link SampleConflict}
 * instead of overwriting this row, and corrected values go to {@link PointAmendment}.
 */
@Entity
@Table(name = "sample_point",
        uniqueConstraints = @UniqueConstraint(name = "uk_run_seq", columnNames = {"run_id", "seq"}),
        indexes = @Index(name = "ix_sample_ts", columnList = "run_id,tsMs"))
public class SamplePoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private long runId;

    /** Device-assigned per-run monotonically increasing sequence number. */
    @Column(nullable = false)
    private long seq;

    /** Device clock reading at measurement time (run-relative virtual ms). */
    @Column(nullable = false)
    private long tsMs;

    /** Millivolts. */
    @Column(nullable = false)
    private int voltageMv;

    /** Milliamps, sign = direction: + charging, - discharging, 0 at rest. */
    @Column(nullable = false)
    private int currentMa;

    /** Deci-degrees Celsius (e.g. 254 == 25.4 C). */
    @Column(nullable = false)
    private int temperatureCd;

    /** Direction code: +1 charge, -1 discharge, 0 rest. */
    @Column(nullable = false)
    private int direction;

    /**
     * Set when the point is confirmed strictly after a point with a larger timestamp
     * (an out-of-order report). Late points never move already published steps; they
     * flag derivations for review instead.
     */
    @Column(nullable = false)
    private boolean late = false;

    /** Wall-clock time of confirmation in the lab (audit only, never used in integration). */
    @Column(nullable = false)
    private long confirmedAtWallMs;

    protected SamplePoint() {
    }

    public SamplePoint(long runId, long seq, long tsMs, int voltageMv, int currentMa,
                       int temperatureCd, int direction, long confirmedAtWallMs) {
        this.runId = runId;
        this.seq = seq;
        this.tsMs = tsMs;
        this.voltageMv = voltageMv;
        this.currentMa = currentMa;
        this.temperatureCd = temperatureCd;
        this.direction = direction;
        this.confirmedAtWallMs = confirmedAtWallMs;
    }

    public Long getId() {
        return id;
    }

    public long getRunId() {
        return runId;
    }

    public long getSeq() {
        return seq;
    }

    public long getTsMs() {
        return tsMs;
    }

    public int getVoltageMv() {
        return voltageMv;
    }

    public int getCurrentMa() {
        return currentMa;
    }

    public int getTemperatureCd() {
        return temperatureCd;
    }

    public int getDirection() {
        return direction;
    }

    public boolean isLate() {
        return late;
    }

    public void setLate(boolean late) {
        this.late = late;
    }

    public long getConfirmedAtWallMs() {
        return confirmedAtWallMs;
    }
}
