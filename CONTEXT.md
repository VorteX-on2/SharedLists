# Shared Lists

Shared Lists coordinates simple lists among devices belonging to one small, trusted group.

## Language

**Trusted group**:
The people and devices that participate in one Shared Lists deployment and share the same collection of lists.
_Avoid_: Tenant, organization, workspace

**Enrolled device**:
A device explicitly authorized to participate in the trusted group.
_Avoid_: User, account, member

**Device enrollment**:
The act of authorizing one device to participate in the trusted group using a credential unique to that device.
_Avoid_: Login, registration, account creation

**Unconfigured device**:
A device on which Shared Lists has no usable device credential.
_Avoid_: Logged-out device

**Unenrolled device**:
A configured device whose credential is not authorized by the trusted group's server.
_Avoid_: Unknown user, unregistered account

**Shared list**:
A generic named collection of list items visible and editable by every enrolled device. Its identity remains stable when its name changes; shopping and todo are uses, not distinct list types.
_Avoid_: Board, document

**List item**:
A distinct entry in a shared list that can be edited, reordered, marked, or unmarked without changing its identity.
_Avoid_: Task, record

**Canonical order**:
The shared, persistent sequence of list items. Temporary client-side sorting changes presentation without changing canonical order.
_Avoid_: Current sort, display order

**Canonical state**:
The server-accepted state toward which every enrolled device converges after synchronization.
_Avoid_: Local state, latest device state

**Synchronization cursor**:
The durable position through which a device has applied canonical changes, identified by the canonical-state generation and its last applied revision.
_Avoid_: Offset, timestamp, checkpoint

**Unconfirmed operation**:
The single edit submitted by a live device whose durable server journal outcome has not yet been received.
_Avoid_: Pending operation, offline edit

**Server identity**:
The persistent self-signed TLS certificate that identifies one Shared Lists server and is trusted by enrolled devices through its SHA-256 fingerprint.
_Avoid_: Device key, public certificate
