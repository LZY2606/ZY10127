package com.example.batterylab.domain;

import jakarta.persistence.*;

/**
 * Researcher correction of a cycle boundary. It stores the raw-point timestamp chosen by
 * the researcher for a cycle number; derivation snaps it to that point and records it.
 */
@Entity
@Table(name = "boundary_override",
        uniqueConstraints = @UniqueConstraint(columnNames = {"runId", "cycleNo"}))
public class BoundaryOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long runId;

    /** End boundary of this cycle (1-based) is moved to the point at boundaryTsMs. */
    @Column(nullable = false)
    private int cycleNo;

    @Column(nullable = false)
    private long boundaryTsMs;

    @Column(nullable = false)
    private String note;

    @Column(nullable = false)
    private long createdAtWallMs;

    protected BoundaryOverride() {
    }

    public BoundaryOverride(long runId, int cycleNo, long boundaryTsMs, String note, long createdAtWallMs) {
        this.runId = runId;
        this.cycleNo = cycleNo;
        this.boundaryTsMs = boundaryTsMs;
        this.note = note;
        this.createdAtWallMs = createdAtWallMs;
    }

    public Long getId() {
        return id;
    }

    public long getRunId() {
        return runId;
    }

    public int getCycleNo() {
        return cycleNo;
    }

    public long getBoundaryTsMs() {
        return boundaryTsMs;
    }

    public String getNote() {
        return note;
    }

    public long getCreatedAtWallMs() {
        return createdAtWallMs;
    }
}
