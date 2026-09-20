package com.example.batterylab.service;

/** A device-reported measurement point (numbers in device integer units). */
public record SampleReport(long seq, long tsMs, int voltageMv, int currentMa,
                           int temperatureCd, int direction) {

    public boolean payloadEquals(SampleReport o) {
        return tsMs == o.tsMs && voltageMv == o.voltageMv && currentMa == o.currentMa
                && temperatureCd == o.temperatureCd && direction == o.direction;
    }
}
