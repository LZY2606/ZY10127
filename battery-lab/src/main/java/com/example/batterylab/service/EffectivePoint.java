package com.example.batterylab.service;

import com.example.batterylab.domain.PointAmendment;
import com.example.batterylab.domain.SamplePoint;

/**
 * A raw point as seen by a derivation: the confirmed values, replaced by an accepted
 * amendment when the researcher chose ACCEPT_NEW on a conflict.
 */
public record EffectivePoint(long id, long seq, long tsMs, int voltageMv, int currentMa,
                             int temperatureCd, int direction, boolean late,
                             boolean amended) {

    public static EffectivePoint of(SamplePoint p, PointAmendment amendment) {
        if (amendment == null) {
            return new EffectivePoint(p.getId(), p.getSeq(), p.getTsMs(), p.getVoltageMv(),
                    p.getCurrentMa(), p.getTemperatureCd(), p.getDirection(), p.isLate(), false);
        }
        return new EffectivePoint(p.getId(), p.getSeq(), p.getTsMs(),
                amendment.getVoltageMv(), amendment.getCurrentMa(),
                amendment.getTemperatureCd(), amendment.getDirection(), p.isLate(), true);
    }
}
