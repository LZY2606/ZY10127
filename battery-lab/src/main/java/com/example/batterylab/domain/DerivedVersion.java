package com.example.batterylab.domain;

import jakarta.persistence.*;

/**
 * An immutable derived result set for a run under a specific rule version and a specific
 * set of researcher inputs (exclusions + boundary overrides). Re-derivation appends a new
 * row and supersedes the previous one; old rows are never rewritten in place.
 */
@Entity
@Table(name = "derived_version")
public class DerivedVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long runId;

    @Column(nullable = false)
    private int versionNo;

    @Column(nullable = false)
    private long ruleVersionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DerivedVersionStatus status;

    /** Snapshot of exclusion ids and boundary overrides included in this derivation. */
    @Lob
    @Column(nullable = false)
    private String inputsJson;

    @Lob
    @Column(nullable = false)
    private String ruleSpecSnapshotJson;

    /** Review flags, e.g. ["LATE_SAMPLE","EXCLUDED_INTERVAL"]; empty means clean. */
    @Lob
    @Column(nullable = false)
    private String reviewFlagsJson = "[]";

    @Column(nullable = false)
    private long createdAtWallMs;

    /** Human-readable reason this version exists. */
    @Column(nullable = false)
    private String reason;

    protected DerivedVersion() {
    }

    public DerivedVersion(long runId, int versionNo, long ruleVersionId,
                          DerivedVersionStatus status, String inputsJson,
                          String ruleSpecSnapshotJson, String reviewFlagsJson,
                          long createdAtWallMs, String reason) {
        this.runId = runId;
        this.versionNo = versionNo;
        this.ruleVersionId = ruleVersionId;
        this.status = status;
        this.inputsJson = inputsJson;
        this.ruleSpecSnapshotJson = ruleSpecSnapshotJson;
        this.reviewFlagsJson = reviewFlagsJson;
        this.createdAtWallMs = createdAtWallMs;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public long getRunId() {
        return runId;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public long getRuleVersionId() {
        return ruleVersionId;
    }

    public DerivedVersionStatus getStatus() {
        return status;
    }

    public void setStatus(DerivedVersionStatus status) {
        this.status = status;
    }

    public String getInputsJson() {
        return inputsJson;
    }

    public String getRuleSpecSnapshotJson() {
        return ruleSpecSnapshotJson;
    }

    public String getReviewFlagsJson() {
        return reviewFlagsJson;
    }

    public long getCreatedAtWallMs() {
        return createdAtWallMs;
    }

    public String getReason() {
        return reason;
    }
}
