package com.example.batterylab.repository;

import com.example.batterylab.domain.Models.RunState;
import com.example.batterylab.domain.Models.SampleStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class LabRepository {
    private final JdbcTemplate jdbc;

    public LabRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> findVersionByVersion(String table, String version) {
        return jdbc.queryForMap("select * from " + table + " where version = ?", version);
    }

    public Map<String, Object> findVersionById(String table, String id) {
        return jdbc.queryForMap("select * from " + table + " where id = ?", id);
    }

    public List<Map<String, Object>> listVersions(String table) {
        return jdbc.queryForList("select * from " + table + " order by created_at_ms, version");
    }

    public Map<String, Object> getRun(String runId) {
        return jdbc.queryForMap("select * from runs where id = ?", runId);
    }

    public Optional<Map<String, Object>> findRun(String runId) {
        return jdbc.query("select * from runs where id = ?", rowMapper(), runId).stream().findFirst();
    }

    public List<Map<String, Object>> listRuns() {
        return jdbc.query("select * from runs order by created_at_ms desc", rowMapper());
    }

    public void insertRun(Object[] args) {
        jdbc.update("""
                insert into runs(id, device_sn, protocol_version_id, protocol_json, rule_version_id,
                  rule_json, capability_version_id, capability_json, state, interruption_reason,
                  current_step_index, current_cycle, cycle_limit, entered_at_ms, step_elapsed_ms,
                  virtual_time_ms, last_confirmed_time_ms, voltage_v, current_a, temperature_c,
                  created_at_ms, ended_at_ms)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, args);
    }

    public void insertDevice(String runId, String deviceSn, long at) {
        jdbc.update("insert into devices(run_id, device_sn, online, connected, updated_at_ms) values(?,?,1,1,?)",
                runId, deviceSn, at);
    }

    public Map<String, Object> getDevice(String runId) {
        return jdbc.queryForMap("select * from devices where run_id = ?", runId);
    }

    public void updateDevice(String runId, boolean online, boolean connected, long at) {
        jdbc.update("update devices set online=?, connected=?, updated_at_ms=? where run_id=?",
                online ? 1 : 0, connected ? 1 : 0, at, runId);
    }

    public Optional<Map<String, Object>> findSample(String runId, String deviceSn, long seq) {
        return jdbc.query("""
                select * from samples where run_id=? and device_sn=? and sequence_number=?
                """, rowMapper(), runId, deviceSn, seq).stream().findFirst();
    }

    public Optional<Map<String, Object>> findPendingSample(String runId) {
        return jdbc.query("""
                select * from samples where run_id=? and status='PENDING'
                order by sequence_number desc limit 1
                """, rowMapper(), runId).stream().findFirst();
    }

    public void insertSample(Object[] args) {
        jdbc.update("""
                insert into samples(id, run_id, device_sn, sequence_number, sampled_at_ms, voltage_v,
                  current_a, temperature_c, payload_hash, status, created_at_ms, confirmed_at_ms,
                  late_after_derivation_id)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, args);
    }

    public void insertConflictAttempt(String id, String sampleId, String payloadJson, String hash, long at) {
        jdbc.update("insert into conflict_attempts(id, sample_id, payload_json, payload_hash, created_at_ms) values(?,?,?,?,?)",
                id, sampleId, payloadJson, hash, at);
    }

    public List<Map<String, Object>> listSamples(String runId) {
        return jdbc.query("select * from samples where run_id=? order by sampled_at_ms, sequence_number",
                rowMapper(), runId);
    }

    public List<Map<String, Object>> listEvents(String runId) {
        return jdbc.query("select * from run_events where run_id=? order by at_ms, id", rowMapper(), runId);
    }

    public void insertEvent(Object[] args) {
        jdbc.update("""
                insert into run_events(id, run_id, event_type, at_ms, from_step_index, to_step_index,
                  from_cycle, to_cycle, from_state, to_state, reason, detail_json)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                """, args);
    }

    public void updateSampleStatus(String sampleId, SampleStatus status, Long confirmedAt, String lateDerivationId) {
        jdbc.update("update samples set status=?, confirmed_at_ms=?, late_after_derivation_id=? where id=?",
                status.name(), confirmedAt, lateDerivationId, sampleId);
    }

    public void updateRunHead(Object[] args) {
        jdbc.update("""
                update runs set state=?, interruption_reason=?, current_step_index=?, current_cycle=?,
                  entered_at_ms=?, step_elapsed_ms=?, virtual_time_ms=?, last_confirmed_time_ms=?,
                  voltage_v=?, current_a=?, temperature_c=?, ended_at_ms=?
                where id=?
                """, args);
    }

    public List<Map<String, Object>> listBoundaryOverrides(String runId) {
        return jdbc.query("select * from boundary_overrides where run_id=? order by cycle_index",
                rowMapper(), runId);
    }

    public void putBoundaryOverride(String runId, int cycle, long at, String reason, long createdAt) {
        jdbc.update("""
                insert into boundary_overrides(run_id, cycle_index, boundary_at_ms, reason, created_at_ms)
                values(?,?,?,?,?)
                on conflict(run_id, cycle_index) do update set
                  boundary_at_ms=excluded.boundary_at_ms,
                  reason=excluded.reason,
                  created_at_ms=excluded.created_at_ms
                """, runId, cycle, at, reason, createdAt);
    }

    public List<Map<String, Object>> listExclusions(String runId) {
        return jdbc.query("select * from exclusions where run_id=? order by start_at_ms, end_at_ms",
                rowMapper(), runId);
    }

    public void insertExclusion(String id, String runId, long start, long end, String reason, long createdAt) {
        jdbc.update("insert into exclusions(id, run_id, start_at_ms, end_at_ms, reason, created_at_ms) values(?,?,?,?,?,?)",
                id, runId, start, end, reason, createdAt);
    }

    public int nextDerivationNumber(String runId) {
        Integer max = jdbc.queryForObject("select coalesce(max(version_number),0) from derivations where run_id=?",
                Integer.class, runId);
        return (max == null ? 0 : max) + 1;
    }

    public Optional<Map<String, Object>> findLatestDerivation(String runId) {
        return jdbc.query("select * from derivations where run_id=? order by version_number desc limit 1",
                rowMapper(), runId).stream().findFirst();
    }

    public Map<String, Object> getDerivation(String id) {
        return jdbc.queryForMap("select * from derivations where id=?", id);
    }

    public List<Map<String, Object>> listDerivations(String runId) {
        return jdbc.query("select * from derivations where run_id=? order by version_number", rowMapper(), runId);
    }

    public void insertDerivation(Object[] args) {
        jdbc.update("""
                insert into derivations(id, run_id, version_number, parent_id, source, rule_version_id,
                  rule_version, rule_json, boundaries_json, exclusions_json, needs_review,
                  review_reason, created_at_ms)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, args);
    }

    public void insertCycleResult(Object[] args) {
        jdbc.update("""
                insert into cycle_results(id, derivation_id, cycle_index, start_at_ms, end_at_ms,
                  charge_ah, discharge_ah, coulombic_efficiency, sample_count, excluded_ms)
                values (?,?,?,?,?,?,?,?,?,?)
                """, args);
    }

    public List<Map<String, Object>> listCycleResults(String derivationId) {
        return jdbc.query("select * from cycle_results where derivation_id=? order by cycle_index",
                rowMapper(), derivationId);
    }

    private RowMapper<Map<String, Object>> rowMapper() {
        return (rs, rowNum) -> {
            var meta = rs.getMetaData();
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                row.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            return row;
        };
    }
}
