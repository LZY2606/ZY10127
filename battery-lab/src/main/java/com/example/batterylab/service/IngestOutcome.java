package com.example.batterylab.service;

/** Result of attempting to confirm one reported sample. */
public record IngestOutcome(Kind kind, boolean stepAdvanced, boolean cycleClosed,
                            String detail) {

    public enum Kind {
        /** New point confirmed and applied to the protocol. */
        CONFIRMED,
        /** Same seq, byte-identical payload: idempotent replay, nothing stored twice. */
        DUPLICATE_IGNORED,
        /** Same seq, different payload: conflict opened, protocol parked. */
        CONFLICT,
        /** Run is terminal; the report is refused and never persisted. */
        REFUSED_TERMINAL,
        /** An open conflict blocks new sequences until the researcher resolves it. */
        REFUSED_BLOCKED
    }

    public static IngestOutcome confirmed(boolean advanced, boolean cycleClosed) {
        return new IngestOutcome(Kind.CONFIRMED, advanced, cycleClosed, null);
    }
}
