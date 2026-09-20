CREATE TABLE IF NOT EXISTS protocol_versions (
  id TEXT PRIMARY KEY,
  version TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  content_json TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS rule_versions (
  id TEXT PRIMARY KEY,
  version TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  content_json TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS device_capabilities (
  id TEXT PRIMARY KEY,
  version TEXT NOT NULL UNIQUE,
  device_sn TEXT NOT NULL,
  content_json TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS runs (
  id TEXT PRIMARY KEY,
  device_sn TEXT NOT NULL,
  protocol_version_id TEXT NOT NULL,
  protocol_json TEXT NOT NULL,
  rule_version_id TEXT NOT NULL,
  rule_json TEXT NOT NULL,
  capability_version_id TEXT NOT NULL,
  capability_json TEXT NOT NULL,
  state TEXT NOT NULL,
  interruption_reason TEXT,
  current_step_index INTEGER NOT NULL,
  current_cycle INTEGER NOT NULL,
  cycle_limit INTEGER NOT NULL,
  entered_at_ms INTEGER NOT NULL,
  step_elapsed_ms INTEGER NOT NULL,
  virtual_time_ms INTEGER NOT NULL,
  last_confirmed_time_ms INTEGER,
  voltage_v REAL,
  current_a REAL,
  temperature_c REAL,
  created_at_ms INTEGER NOT NULL,
  ended_at_ms INTEGER
);

CREATE TABLE IF NOT EXISTS devices (
  run_id TEXT NOT NULL,
  device_sn TEXT NOT NULL,
  online INTEGER NOT NULL,
  connected INTEGER NOT NULL,
  updated_at_ms INTEGER NOT NULL,
  PRIMARY KEY (run_id, device_sn),
  FOREIGN KEY (run_id) REFERENCES runs(id)
);

CREATE TABLE IF NOT EXISTS samples (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL,
  device_sn TEXT NOT NULL,
  sequence_number INTEGER NOT NULL,
  sampled_at_ms INTEGER NOT NULL,
  voltage_v REAL NOT NULL,
  current_a REAL NOT NULL,
  temperature_c REAL NOT NULL,
  payload_hash TEXT NOT NULL,
  status TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL,
  confirmed_at_ms INTEGER,
  late_after_derivation_id TEXT,
  UNIQUE(run_id, device_sn, sequence_number),
  FOREIGN KEY (run_id) REFERENCES runs(id)
);
CREATE INDEX IF NOT EXISTS idx_samples_run_time ON samples(run_id, sampled_at_ms);

CREATE TABLE IF NOT EXISTS conflict_attempts (
  id TEXT PRIMARY KEY,
  sample_id TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  payload_hash TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL,
  FOREIGN KEY (sample_id) REFERENCES samples(id)
);

CREATE TABLE IF NOT EXISTS run_events (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL,
  event_type TEXT NOT NULL,
  at_ms INTEGER NOT NULL,
  from_step_index INTEGER,
  to_step_index INTEGER,
  from_cycle INTEGER,
  to_cycle INTEGER,
  from_state TEXT,
  to_state TEXT,
  reason TEXT,
  detail_json TEXT
);
CREATE INDEX IF NOT EXISTS idx_events_run_time ON run_events(run_id, at_ms);

CREATE TABLE IF NOT EXISTS boundary_overrides (
  run_id TEXT NOT NULL,
  cycle_index INTEGER NOT NULL,
  boundary_at_ms INTEGER NOT NULL,
  reason TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL,
  PRIMARY KEY (run_id, cycle_index),
  FOREIGN KEY (run_id) REFERENCES runs(id)
);

CREATE TABLE IF NOT EXISTS exclusions (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL,
  start_at_ms INTEGER NOT NULL,
  end_at_ms INTEGER NOT NULL,
  reason TEXT NOT NULL,
  created_at_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_exclusions_run ON exclusions(run_id, start_at_ms, end_at_ms);

CREATE TABLE IF NOT EXISTS derivations (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL,
  version_number INTEGER NOT NULL,
  parent_id TEXT,
  source TEXT NOT NULL,
  rule_version_id TEXT NOT NULL,
  rule_version TEXT NOT NULL,
  rule_json TEXT NOT NULL,
  boundaries_json TEXT NOT NULL,
  exclusions_json TEXT NOT NULL,
  needs_review INTEGER NOT NULL,
  review_reason TEXT,
  created_at_ms INTEGER NOT NULL,
  UNIQUE(run_id, version_number),
  FOREIGN KEY (run_id) REFERENCES runs(id)
);

CREATE TABLE IF NOT EXISTS cycle_results (
  id TEXT PRIMARY KEY,
  derivation_id TEXT NOT NULL,
  cycle_index INTEGER NOT NULL,
  start_at_ms INTEGER,
  end_at_ms INTEGER,
  charge_ah REAL NOT NULL,
  discharge_ah REAL NOT NULL,
  coulombic_efficiency REAL,
  sample_count INTEGER NOT NULL,
  excluded_ms INTEGER NOT NULL,
  UNIQUE(derivation_id, cycle_index),
  FOREIGN KEY (derivation_id) REFERENCES derivations(id)
);
