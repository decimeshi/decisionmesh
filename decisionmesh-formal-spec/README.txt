DecisionMesh TLA+ Specifications

1. IntentLifecycle.tla
   - Formal model of Intent aggregate lifecycle.
   - Verifies retry bounds and terminal state behavior.

2. DistributedProtocol.tla
   - Models region ownership, failover, and event append.
   - Verifies version monotonicity and ownership transitions.

Usage:
- Open in TLA+ Toolbox.
- Load module.
- Run model checker with provided .cfg file.
- Add additional invariants as system evolves.