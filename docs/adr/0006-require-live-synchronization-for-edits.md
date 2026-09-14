# Require live synchronization for edits

Shared Lists permits editing only after a foreground client reaches the live synchronization state; a disconnected or catching-up client may display its cached lists but keeps them read-only. Each edit is rendered only after its authoritative journal entry returns, and the client permits one edit in flight at a time, durably retaining only that unconfirmed operation so an acknowledgement lost during disconnection can be reconciled or retried idempotently.

This deliberately gives up arbitrary offline editing to keep the MVP client shallow: there is no offline operation queue, optimistic overlay across sessions, conflict inbox, or batch recovery workflow. Foreground connection failures retry every five seconds, while explicit server faults stop automatic retry; authentication failures use the blocking enrollment flow, and a superseded session stays read-only until the user explicitly takes over synchronization.
