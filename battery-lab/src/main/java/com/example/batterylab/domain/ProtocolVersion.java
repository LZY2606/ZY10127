package com.example.batterylab.domain;

import jakarta.persistence.*;

/**
 * An immutable, independently versioned test protocol.
 * Body is JSON: { "steps": [ {"type":"CC_CHARGE","currentMa":..,"limitMv":..}, ... ] }.
 */
@Entity
@Table(name = "protocol_version",
        uniqueConstraints = @UniqueConstraint(columnNames = {"name", "version"}))
public class ProtocolVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String version;

    @Lob
    @Column(nullable = false)
    private String bodyJson;

    @Column(nullable = false)
    private long createdAtMs;

    protected ProtocolVersion() {
    }

    public ProtocolVersion(String name, String version, String bodyJson, long createdAtMs) {
        this.name = name;
        this.version = version;
        this.bodyJson = bodyJson;
        this.createdAtMs = createdAtMs;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public String getBodyJson() {
        return bodyJson;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }
}
