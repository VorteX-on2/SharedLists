# Store current state and idempotency in SQLite

The single Shared Lists server stores relational current state, permanent tombstones, and an append-only operation journal in one local SQLite database using rollback journaling, full synchronous durability, and a serialized database actor. Each operation updates state and appends its canonical payload and outcome atomically before acknowledgement; the journal provides idempotent retries and incremental synchronization while current-state tables provide snapshots and efficient reads, and the server fails closed on storage or schema errors.
