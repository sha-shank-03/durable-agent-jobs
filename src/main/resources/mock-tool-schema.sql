CREATE TABLE tool_invocations (
  invocation_id INTEGER PRIMARY KEY AUTOINCREMENT,
  invocation_key TEXT NOT NULL,
  args_hash TEXT NOT NULL,
  outcome TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE TABLE ticket_receipts (
  invocation_key TEXT PRIMARY KEY,
  args_hash TEXT NOT NULL,
  ticket_id TEXT NOT NULL UNIQUE,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  receipt_json TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
