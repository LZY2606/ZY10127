package com.example.batterylab.domain;

import jakarta.persistence.*;

/**
 * A sensor-fault interval excluded from derivation. Raw points are kept; the exclusion
 * is only applied when (re)computing a derived version, so old results stay intact.
 */
@Entity
@Table(name = "point_exclusion")
public class PointExclusion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long runId;

    @Column(nullable = false)
    private long fromTsMs;

    @Column(nullable = false)
    private long toTsMs;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private long createdAtWallMs;

    protected PointExclusion() {
    }

    public PointExclusion(long runId, long fromTsMs, long toTsMs, String reason, long createdAtWallMs) {
        this.runId = runId;
        this.fromTsMs = fromTsMs;
        this.toTsMs = toTsMs;
        this.reason = reason;
        this.createdAtWallMs = createdAtWallMs;
    }

    public Long getId() {
        return id;
    }

    public long getRunId() {
        return runId;
    }

    public long getFromTsMs() {
        return fromTsMs;
    }

    public long getToTsMs() {
        return toTsMs;
    }

    public String getReason() {
        return reason;
    }

    public long getCreatedAtWallMs() {
        return createdAtWallMs;
    }
}
