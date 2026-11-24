ALTER TABLE kafka_publication ADD COLUMN trace_parent VARCHAR(55);
ALTER TABLE outbound_message ADD COLUMN trace_parent VARCHAR(55);
