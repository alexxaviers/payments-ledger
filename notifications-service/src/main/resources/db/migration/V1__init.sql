-- One row per event we have acted on. The PRIMARY KEY is what makes the consumer idempotent:
-- a redelivered event fails to insert here and is skipped.
CREATE TABLE processed_events (
  event_id       UUID PRIMARY KEY,
  transaction_id UUID NOT NULL,
  processed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Every delivery Kafka hands us, duplicates included, so we can measure how often at-least-once
-- delivery actually redelivered something.
CREATE TABLE deliveries (
  id          BIGSERIAL PRIMARY KEY,
  event_id    UUID NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX deliveries_event_idx ON deliveries (event_id);

-- The side effect: one notification per customer account involved in the transfer.
CREATE TABLE notifications (
  id          BIGSERIAL PRIMARY KEY,
  event_id    UUID NOT NULL REFERENCES processed_events(event_id),
  account_id  UUID NOT NULL,
  message     TEXT NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
