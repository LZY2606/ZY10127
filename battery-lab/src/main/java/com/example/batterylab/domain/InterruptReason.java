package com.example.batterylab.domain;

/**
 * Why a cycle stopped. Pause, cancellation, device fault and normal completion are kept
 * distinct so a reviewer can tell an interrupted cycle from a clean one.
 */
public enum InterruptReason {
    NORMAL,
    PAUSED,
    CANCELLED,
    DEVICE_FAULT,
    CONFLICT_BLOCKED
}
