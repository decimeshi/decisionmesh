----------------------------- MODULE DistributedProtocol -----------------------------
EXTENDS Naturals, Sequences

CONSTANTS Regions

VARIABLES owner, version, ledgerHash

Init ==
    /\ owner \in Regions
    /\ version = 0
    /\ ledgerHash = "GENESIS"

AppendEvent ==
    /\ version' = version + 1
    /\ ledgerHash' = Hash(version', ledgerHash)
    /\ UNCHANGED owner

Failover ==
    /\ owner' \in Regions
    /\ owner' # owner
    /\ UNCHANGED <<version, ledgerHash>>

Hash(v, prev) ==
    <<v, prev>>

Next ==
    \/ AppendEvent
    \/ Failover

InvariantMonotonicVersion == version >= 0

Spec == Init /\ [][Next]_<<owner, version, ledgerHash>>

=============================================================================