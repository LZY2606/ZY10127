package com.example.batterylab.sim;

import com.example.batterylab.service.IngestOutcome;

import java.util.ArrayList;
import java.util.List;

/** What happened during one deterministic simulator tick. */
public class TickResult {
    public long generated;
    public long delivered;
    public long buffered;
    public boolean blockedByConflict;
    public boolean completed;
    public final List<IngestOutcome> outcomes = new ArrayList<>();
}
