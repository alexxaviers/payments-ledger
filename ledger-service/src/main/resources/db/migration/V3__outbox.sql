-- Transactional outbox: events are written in the SAME database transaction as the transfer, so a
-- transfer and its event commit together or not at all. A separate publisher ships them to Kafka.
CREATE TABLE outbox (
  id            UUID PRIMARY KEY,
  aggregate_id  UUID NOT NULL,              -- the transaction the event is about (Kafka message key)
  event_type    TEXT NOT NULL,
  payload       JSONB NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at  TIMESTAMPTZ NULL
);

-- The publisher only ever looks for unpublished rows, oldest first.
CREATE INDEX outbox_unpublished_idx ON outbox (created_at) WHERE published_at IS NULL;
