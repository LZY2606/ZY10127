package com.example.batterylab.domain;

/**
 * Life-cycle of a run.
 * PAUSED is the only status from which a run may be continued after restart;
 * COMPLETED / CANCELLED / DEVICE_FAULT are terminal and can never be revived.
 */
public enum RunStatus {
    CREATED,
    RUNNING,
    PAUSED,
    COMPLETED,
    CANCELLED,
    DEVICE_FAULT
}
