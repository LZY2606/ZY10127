package com.example.batterylab.domain;

import jakarta.persistence.*;

/** Researcher-accepted corrected payload for one raw point (conflict ACCEPT_NEW). */
@Entity
@Table(name = "point_amendment")
public class PointAmendment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long samplePointId;

    @Column(nullable = false)
    private long runId;

    @Column(nullable = false)
    private long seq;

    @Column(nullable = false)
    private int voltageMv;
    @Column(nullable = false)
    private int currentMa;
    @Column(nullable = false)
    private int temperatureCd;
    @Column(nullable = false)
    private int direction;

    @Column(nullable = false)
    private long createdAtWallMs;

    protected PointAmendment() {
    }

    public PointAmendment(long samplePointId, long runId, long seq,
                          int voltageMv, int currentMa, int temperatureCd, int direction,
                          long createdAtWallMs) {
        this.samplePointId = samplePointId;
        this.runId = runId;
        this.seq = seq;
        this.voltageMv = voltageMv;
        this.currentMa = currentMa;
        this.temperatureCd = temperatureCd;
        this.direction = direction;
        this.createdAtWallMs = createdAtWallMs;
    }

    public Long getId() {
        return id;
    }

    public long getSamplePointId() {
        return samplePointId;
    }

    public long getRunId() {
        return runId;
    }

    public long getSeq() {
        return seq;
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

    public long getCreatedAtWallMs() {
        return createdAtWallMs;
    }
}
