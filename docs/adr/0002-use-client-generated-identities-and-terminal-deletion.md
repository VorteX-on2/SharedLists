# Use client-generated identities and terminal deletion

Shared Lists gives every list and item an immutable client-generated UUIDv4 so creation and subsequent edits can proceed offline without identity remapping. Names and item text remain editable content, deletion is terminal so delayed operations cannot resurrect an identity, and each list retains one shared manual item order while temporary client-side sorting changes only presentation; these rules favor deterministic synchronization over server-assigned identities, restoration, or synchronized view preferences.
