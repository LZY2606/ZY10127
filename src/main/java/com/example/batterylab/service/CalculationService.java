package com.example.batterylab.service;

import com.example.batterylab.domain.Models.RuleDefinition;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class CalculationService {
    public record Exclusion(long startMs, long endMs, String reason) {
    }

    private record PrioritizedSamplePoint(long timeMs, double currentA, int priority) {
    }

    public record CycleCalculation(
            int cycleIndex,
            Long startMs,
            Long endMs,
            double chargeAh,
            double dischargeAh,
            Double efficiency,
            int sampleCount,
            long excludedMs
    ) {
    }

    public List<CycleCalculation> calculate(
            List<Map<String, Object>> confirmedSamples,
            List<Long> boundaries,
            List<Long> stepBoundaries,
            List<Exclusion> exclusions,
            RuleDefinition rule
    ) {
        List<PrioritizedSamplePoint> points = confirmedSamples.stream()
                .filter(sample -> "CONFIRMED".equals(String.valueOf(sample.get("status")))
                        || "LATE".equals(String.valueOf(sample.get("status"))))
                .map(sample -> new PrioritizedSamplePoint(
                        ((Number) sample.get("sampled_at_ms")).longValue(),
                        ((Number) sample.get("current_a")).doubleValue(),
                        "CONFIRMED".equals(String.valueOf(sample.get("status"))) ? 0 : 1))
                .sorted(Comparator.comparingLong(PrioritizedSamplePoint::timeMs)
                        .thenComparingInt(PrioritizedSamplePoint::priority))
                .toList();

        List<Long> safeBoundaries = boundaries.stream().distinct().sorted().toList();
        List<CycleCalculation> results = new ArrayList<>();
        if (points.isEmpty()) {
            return results;
        }
        long firstTime = points.get(0).timeMs();
        long lastTime = points.get(points.size() - 1).timeMs();
        for (int cycle = 1; cycle < safeBoundaries.size(); cycle++) {
            long boundary = safeBoundaries.get(cycle - 1);
            long nextBoundary = safeBoundaries.get(cycle);
            if (nextBoundary < firstTime || boundary > lastTime) {
                continue;
            }
            results.add(calculateCycle(cycle, points, boundary, nextBoundary, stepBoundaries, exclusions, rule));
        }
        return results;
    }

    private CycleCalculation calculateCycle(
            int cycleIndex,
            List<PrioritizedSamplePoint> points,
            long start,
            long end,
            List<Long> allStepBoundaries,
            List<Exclusion> exclusions,
            RuleDefinition rule
    ) {
        List<PrioritizedSamplePoint> cyclePoints = points.stream()
                .filter(point -> point.timeMs() >= start && point.timeMs() <= end)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        if (cyclePoints.isEmpty()) {
            return new CycleCalculation(cycleIndex, null, null, 0, 0, null, 0, 0);
        }

        List<Long> stepBoundaries = allStepBoundaries.stream()
                .filter(time -> time > start && time < end)
                .distinct()
                .sorted()
                .toList();
        List<Segment> segments = buildSegments(cyclePoints, start, end, stepBoundaries, rule);
        List<Segment> usable = new ArrayList<>();
        long excludedMs = 0;
        for (Segment segment : segments) {
            long cursor = segment.leftMs();
            List<Exclusion> covering = exclusions.stream()
                    .filter(exclusion -> exclusion.endMs() > segment.leftMs()
                            && exclusion.startMs() < segment.rightMs())
                    .sorted(Comparator.comparingLong(Exclusion::startMs))
                    .toList();
            for (Exclusion exclusion : covering) {
                long left = Math.max(cursor, exclusion.startMs());
                long right = Math.min(segment.rightMs(), exclusion.endMs());
                if (right <= left) {
                    continue;
                }
                if (left > cursor) {
                    usable.add(slice(segment, cursor, left, rule));
                }
                excludedMs += right - left;
                cursor = right;
            }
            if (cursor < segment.rightMs()) {
                usable.add(slice(segment, cursor, segment.rightMs(), rule));
            }
        }

        double chargeAs = 0.0;
        double dischargeAs = 0.0;
        for (Segment segment : usable) {
            double integrated = switch (rule.integrationMethod()) {
                case "LEFT_RECTANGLE" -> segment.leftA() * segment.durationMs() / 1000.0;
                case "TRAPEZOID" -> (segment.leftA() + segment.rightA()) / 2.0 * segment.durationMs() / 1000.0;
                default -> throw new IllegalArgumentException("Unsupported integration method: " + rule.integrationMethod());
            };
            if (integrated > 0) {
                chargeAs += integrated;
            } else if (integrated < 0) {
                dischargeAs += -integrated;
            }
        }

        double chargeAh = round(chargeAs / 3600.0);
        double dischargeAh = round(dischargeAs / 3600.0);
        Double efficiency = chargeAh == 0.0 ? null : round(dischargeAh / chargeAh);
        int sampleCount = (int) cyclePoints.stream()
                .filter(point -> !isPointExcluded(point.timeMs(), exclusions))
                .count();
        return new CycleCalculation(
                cycleIndex,
                cyclePoints.get(0).timeMs(),
                cyclePoints.get(cyclePoints.size() - 1).timeMs(),
                chargeAh,
                dischargeAh,
                efficiency,
                sampleCount,
                excludedMs
        );
    }

    private Segment slice(Segment segment, long left, long right, RuleDefinition rule) {
        double leftCurrent = currentBetween(segment, left, rule);
        double rightCurrent = currentBetween(segment, right, rule);
        return new Segment(left, right, leftCurrent, rightCurrent);
    }

    private double currentBetween(Segment segment, long time, RuleDefinition rule) {
        if ("LEFT_RECTANGLE".equals(rule.integrationMethod()) || time == segment.leftMs()) {
            return segment.leftA();
        }
        if (time == segment.rightMs()) {
            return segment.rightA();
        }
        double ratio = (double) (time - segment.leftMs()) / segment.durationMs();
        return segment.leftA() + (segment.rightA() - segment.leftA()) * ratio;
    }

    private List<Segment> buildSegments(
            List<PrioritizedSamplePoint> points,
            long start,
            long end,
            List<Long> stepBoundaries,
            RuleDefinition rule
    ) {
        List<Long> cuts = new ArrayList<>();
        for (PrioritizedSamplePoint point : points) {
            if (point.timeMs() >= start && point.timeMs() <= end) {
                cuts.add(point.timeMs());
            }
        }
        cuts.add(end);
        for (Long boundary : stepBoundaries) {
            cuts.add(boundary);
        }
        cuts.sort(Long::compare);
        cuts = new ArrayList<>(cuts.stream().distinct().toList());

        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < cuts.size() - 1; i++) {
            long left = cuts.get(i);
            long right = cuts.get(i + 1);
            if (right <= left) {
                continue;
            }
            boolean leftStepBoundary = left > start && stepBoundaries.contains(left);
            boolean rightStepBoundary = right < end && stepBoundaries.contains(right);
            if ("LEFT_RECTANGLE".equals(rule.integrationMethod())
                    && "ASSIGN_ENDPOINT_TO_OUTGOING_STEP".equals(rule.endpointPolicy())
                    && left == start && left > points.get(0).timeMs()) {
                continue;
            }
            double leftCurrent = boundaryCurrent(leftStepBoundary, rule) ? 0.0
                    : currentAt(points, left, rule, start, left == start && left == points.get(0).timeMs());
            double rightCurrent = boundaryCurrent(rightStepBoundary, rule) ? 0.0
                    : currentAt(points, right, rule, start, false);
            segments.add(new Segment(left, right, leftCurrent, rightCurrent));
        }
        return segments;
    }

    private boolean boundaryCurrent(boolean boundary, RuleDefinition rule) {
        return boundary && "TRAPEZOID".equals(rule.integrationMethod())
                && "ZERO_CURRENT_AT_STEP_BOUNDARY".equals(rule.endpointPolicy());
    }

    private double currentAt(
            List<PrioritizedSamplePoint> points,
            long time,
            RuleDefinition rule,
            long cycleStart,
            boolean internalStepBoundary
    ) {
        if ("TRAPEZOID".equals(rule.integrationMethod())
                && "ZERO_CURRENT_AT_STEP_BOUNDARY".equals(rule.endpointPolicy())
                && (internalStepBoundary || (time == cycleStart && points.get(0).timeMs() == cycleStart))) {
            return 0.0;
        }
        PrioritizedSamplePoint exact = null;
        PrioritizedSamplePoint before = null;
        PrioritizedSamplePoint after = null;
        for (PrioritizedSamplePoint point : points) {
            if (point.timeMs() == time) {
                if (exact == null || point.priority() < exact.priority()) {
                    exact = point;
                }
            }
            if (point.timeMs() <= time) {
                before = point;
            }
            if (after == null && point.timeMs() >= time) {
                after = point;
            }
        }
        if (exact != null) {
            return exact.currentA();
        }
        if (before != null && after != null && after.timeMs() != before.timeMs()) {
            double ratio = (double) (time - before.timeMs()) / (after.timeMs() - before.timeMs());
            return before.currentA() + (after.currentA() - before.currentA()) * ratio;
        }
        if (before != null) {
            return before.currentA();
        }
        return after == null ? 0.0 : after.currentA();
    }

    private long overlap(Segment segment, List<Exclusion> exclusions) {
        long covered = 0;
        for (Exclusion exclusion : exclusions) {
            long left = Math.max(segment.leftMs(), exclusion.startMs());
            long right = Math.min(segment.rightMs(), exclusion.endMs());
            if (right > left) {
                covered += right - left;
            }
        }
        return Math.min(covered, segment.durationMs());
    }

    private boolean isPointExcluded(long time, List<Exclusion> exclusions) {
        return exclusions.stream()
                .anyMatch(exclusion -> time >= exclusion.startMs() && time < exclusion.endMs());
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(9, RoundingMode.HALF_UP).doubleValue();
    }

    private record Segment(long leftMs, long rightMs, double leftA, double rightA) {
        long durationMs() {
            return rightMs - leftMs;
        }
    }
}
