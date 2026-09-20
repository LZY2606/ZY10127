package com.example.batterylab.domain;

/** Step kinds understood by the protocol interpreter. */
public enum StepType {
    /** Constant current charge until an upper voltage limit is confirmed. */
    CC_CHARGE,
    /** Constant voltage charge held at a voltage until the current falls below the cut-off. */
    CV_CUTOFF,
    /** Zero-current hold for a fixed virtual duration. */
    REST,
    /** Constant current discharge until a lower voltage limit is confirmed. */
    CC_DISCHARGE,
    /** Jump back to an earlier step index for a number of repetitions. */
    LOOP
}
