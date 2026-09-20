package com.example.batterylab.domain;

import jakarta.persistence.*;

/** Independently versioned description of what a device can do and report. */
@Entity
@Table(name = "device_profile",
        uniqueConstraints = @UniqueConstraint(columnNames = {"name", "version"}))
public class DeviceProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String version;

    @Lob
    @Column(nullable = false)
    private String capabilitiesJson;

    @Column(nullable = false)
    private long createdAtMs;

    protected DeviceProfile() {
    }

    public DeviceProfile(String name, String version, String capabilitiesJson, long createdAtMs) {
        this.name = name;
        this.version = version;
        this.capabilitiesJson = capabilitiesJson;
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

    public String getCapabilitiesJson() {
        return capabilitiesJson;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }
}
