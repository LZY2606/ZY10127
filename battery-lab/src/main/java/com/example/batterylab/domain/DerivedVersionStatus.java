package com.example.batterylab.domain;

public enum DerivedVersionStatus {
    /** Published derivation; late samples never rewrite it. */
    PUBLISHED,
    /** A newer recomputation exists; retained but no longer current. */
    SUPERSEDED,
    /** Derivation that flags raw data needing researcher review. */
    NEEDS_REVIEW
}
