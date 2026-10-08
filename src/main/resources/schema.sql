CREATE TABLE jobs (
  job_id TEXT PRIMARY KEY,
  request_key TEXT NOT NULL UNIQUE,
  spec_hash TEXT NOT NULL,
  spec_json TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('QUEUED','RUNNING','WAITING_RETRY','SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','NEEDS_REVIEW')),
  checkpoint_seq INTEGER NOT NULL DEFAULT 0 CHECK(checkpoint_seq >= 0),
  cancel_requested INTEGER NOT NULL DEFAULT 0 CHECK(cancel_requested IN (0,1)),
  deadline_at INTEGER,
  generation TEXT,
  result_json TEXT,
  error_code TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE TABLE checkpoints (
  job_id TEXT NOT NULL REFERENCES jobs(job_id),
  sequence INTEGER NOT NULL CHECK(sequence >= 0),
  state_json TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(job_id, sequence)
);
CREATE TABLE operations (
  job_id TEXT NOT NULL REFERENCES jobs(job_id),
  operation_key TEXT NOT NULL,
  invocation_key TEXT NOT NULL UNIQUE,
  tool_version TEXT NOT NULL,
  contract TEXT NOT NULL CHECK(contract IN ('PURE','IDEMPOTENT_WITH_KEY','NON_IDEMPOTENT')),
  args_hash TEXT NOT NULL,
  args_json TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('INTENT','COMPLETED','FAILED','UNKNOWN')),
  attempt_count INTEGER NOT NULL CHECK(attempt_count >= 1),
  next_attempt_at INTEGER,
  result_json TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY(job_id, operation_key)
);
CREATE TABLE attempts (
  job_id TEXT NOT NULL,
  operation_key TEXT NOT NULL,
  attempt_number INTEGER NOT NULL,
  generation TEXT NOT NULL,
  outcome TEXT NOT NULL,
  started_at INTEGER NOT NULL,
  ended_at INTEGER,
  PRIMARY KEY(job_id, operation_key, attempt_number),
  FOREIGN KEY(job_id, operation_key) REFERENCES operations(job_id, operation_key)
);
CREATE TABLE events (
  job_id TEXT NOT NULL REFERENCES jobs(job_id),
  sequence INTEGER NOT NULL,
  type TEXT NOT NULL,
  details_json TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(job_id, sequence)
);
CREATE INDEX jobs_runnable ON jobs(state, created_at, job_id);
