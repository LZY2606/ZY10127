package com.example.batterylab.domain;

import jakarta.persistence.*;

/**
 * Independently versioned calculation rules: sign convention, endpoint assignment,
 * current dead-band, integration method and temperature limits.
 */
@Entity
@Table(name = "rule_version",
        uniqueConstraints = @UniqueConstraint(columnNames = {"name", "version"}))
public class RuleVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String version;

    @Lob
    @Column(nullable = false)
    private String specJson;

    @Column(nullable = false)
    private long createdAtMs;

    protected RuleVersion() {
    }

    public RuleVersion(String name, String version, String specJson, long createdAtMs) {
        this.name = name;
        this.version = version;
        this.specJson = specJson;
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

    public String getSpecJson() {
        return specJson;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }
}
