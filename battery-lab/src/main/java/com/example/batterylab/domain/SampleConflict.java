package com.example.batterylab.domain;

import jakarta.persistence.*;

/**
 * A duplicate device sequence whose payload differs from the confirmed one.
 * While OPEN the run is parked in a conflict state and the protocol does not advance.
 */
@Entity
@Table(name = "sample_conflict")
public class SampleConflict {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long runId;

    @Column(nullable = false)
    private long seq;

    /** Confirmed payload (already stored on sample_point). */
    @Column(nullable = false)
    private int existingMv;
    @Column(nullable = false)
    private int existingMa;
    @Column(nullable = false)
    private int existingCd;
    @Column(nullable = false)
    private long existingTsMs;
    @Column(nullable = false)
    private int existingDirection;

    /** Conflicting payload reported under the same sequence. */
    @Column(nullable = false)
    private int duplicateMv;
    @Column(nullable = false)
    private int duplicateMa;
    @Column(nullable = false)
    private int duplicateCd;
    @Column(nullable = false)
    private long duplicateTsMs;
    @Column(nullable = false)
    private int duplicateDirection;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConflictStatus status = ConflictStatus.OPEN;

    @Column(nullable = false)
    private long detectedAtWallMs;

    private Long resolvedDerivedFromId;

    protected SampleConflict() {
    }

    public SampleConflict(long runId, long seq,
                          int existingMv, int existingMa, int existingCd,
                          long existingTsMs, int existingDirection,
                          int duplicateMv, int duplicateMa, int duplicateCd,
                          long duplicateTsMs, int duplicateDirection,
                          long detectedAtWallMs) {
        this.runId = runId;
        this.seq = seq;
        this.existingMv = existingMv;
        this.existingMa = existingMa;
        this.existingCd = existingCd;
        this.existingTsMs = existingTsMs;
        this.duplicateMv = duplicateMv;
        this.duplicateMa = duplicateMa;
        this.duplicateCd = duplicateCd;
        this.duplicateTsMs = duplicateTsMs;
        this.existingDirection = existingDirection;
        this.duplicateDirection = duplicateDirection;
        this.detectedAtWallMs = detectedAtWallMs;
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

    public int getExistingMv() {
        return existingMv;
    }

    public int getExistingMa() {
        return existingMa;
    }

    public int getExistingCd() {
        return existingCd;
    }

    public long getExistingTsMs() {
        return existingTsMs;
    }

    public int getExistingDirection() {
        return existingDirection;
    }

    public int getDuplicateMv() {
        return duplicateMv;
    }

    public int getDuplicateMa() {
        return duplicateMa;
    }

    public int getDuplicateCd() {
        return duplicateCd;
    }

    public long getDuplicateTsMs() {
        return duplicateTsMs;
    }

    public int getDuplicateDirection() {
        return duplicateDirection;
    }

    public ConflictStatus getStatus() {
        return status;
    }

    public void setStatus(ConflictStatus status) {
        this.status = status;
    }

    public long getDetectedAtWallMs() {
        return detectedAtWallMs;
    }

    public Long getResolvedDerivedFromId() {
        return resolvedDerivedFromId;
    }

    public void setResolvedDerivedFromId(Long resolvedDerivedFromId) {
        this.resolvedDerivedFromId = resolvedDerivedFromId;
    }
}
