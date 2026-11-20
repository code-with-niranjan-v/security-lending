-- Shared by lender and lifecycle, or local to borrower. All times come from PostgreSQL.
CREATE TABLE platform_worker_lease (
  job VARCHAR(250) PRIMARY KEY,
  owner VARCHAR(250) NOT NULL,
  fence BIGINT NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE platform_work_completion (
  job VARCHAR(120) NOT NULL,
  entity_id BIGINT NOT NULL,
  period VARCHAR(120) NOT NULL,
  completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (job, entity_id, period)
);
