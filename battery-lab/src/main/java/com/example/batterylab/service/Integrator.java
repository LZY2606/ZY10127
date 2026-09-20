package com.example.batterylab.service;

/**
 * Trapezoidal charge integration for unevenly spaced samples.
 *
 * Inputs use integer engineering units: time in ms, current in mA.
 * Output throughput is accumulated in micro-Ah:
 *
 *   dQ(uAh) = ((i1 + i2) / 2) mA * (dt / 3_600_000) h * 1000
 *           = (i1 + i2) * dt / 7200
 *
 * The factor 7200 keeps everything in long/integer arithmetic (exact for these units;
 * no floating point drift between two derivations).
 *
 * A sample that sits exactly on a step/cycle end boundary is attributed, by rule, to the
 * PREVIOUS window: the interval leading INTO the boundary point counts, the interval
 * leaving it does not. Splitting at the boundary point therefore neither drops nor
 * double counts that sample.
 */
public final class Integrator {

    public static final long MICRO_AH_PER_MAH = 1000L;
    private static final long MS_PER_HOUR = 3_600_000L;
    private static final long SCALE = 2L * MS_PER_HOUR / MICRO_AH_PER_MAH; // 7200

    private Integrator() {
    }

    /** Signed throughput of one interval, micro-Ah, trapezoid on the two endpoint currents. */
    public static long intervalUah(int currentMaStart, int currentMaEnd, long dtMs) {
        if (dtMs <= 0) {
            return 0L;
        }
        return Math.addExact((long) currentMaStart + currentMaEnd, 0L) * dtMs / SCALE;
    }

    /** Positive part (charge direction), micro-Ah. */
    public static long chargePart(long signedUah) {
        return Math.max(0L, signedUah);
    }

    /** Negative magnitude (discharge direction), micro-Ah. */
    public static long dischargePart(long signedUah) {
        return Math.max(0L, -signedUah);
    }

    /** Coulombic efficiency in basis points (9876 == 98.76%); -1 when undefined. */
    public static int efficiencyBp(long chargeUah, long dischargeUah) {
        if (chargeUah <= 0) {
            return -1;
        }
        long bp = dischargeUah * 10_000L / chargeUah;
        if (bp > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) bp;
    }
}
