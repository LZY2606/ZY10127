package com.example.batterylab.domain;

/** Researcher decision for an OPEN duplicate-seq conflict. */
public enum ConflictResolution {
    /** Keep the original confirmed payload; the duplicate is discarded. */
    KEEP_EXISTING,
    /** Keep the original row for audit but attach the new values as an amendment. */
    ACCEPT_NEW
}
