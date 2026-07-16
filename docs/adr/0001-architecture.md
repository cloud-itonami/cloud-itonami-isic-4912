# ADR-0001: RailFreight-LLM ⊣ Rail Freight Governor architecture

## Status

Accepted. `cloud-itonami-isic-4912` promoted from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

`cloud-itonami-isic-4912` was scaffolded early as a `:blueprint`-tier
repository -- `README.md`/`docs/business-model.md`/`docs/operator-
guide.md`/`blueprint.edn` were published, but no `deps.edn`/`src`/
`test` ever existed. This ADR records the governed-actor architecture
that fills in that pre-existing blueprint with real, tested code,
following the same langgraph StateGraph + independent Governor +
Phase 0→3 rollout pattern established by `cloud-itonami-isic-6511`
(life insurance) and applied across many prior siblings, most
recently `cloud-itonami-isic-4920` (community road freight).

## Scope decision: coordination actor, not dispatch/track-safety authority

The pre-existing `docs/business-model.md`/`docs/operator-guide.md`
describe a broader aspirational business (safety-management-system
scope, hazmat-transport scope, robotics-assisted inspection,
booking/reconciliation records). This R0 build deliberately
implements a NARROWER, explicitly-scoped slice of that: a freight-
rail OPERATIONS COORDINATION actor with a closed, four-member op
allowlist:

- `:log-shipment-record` -- consist/cargo-manifest/routing data logging
- `:schedule-service-operation` -- train-consist/routing scheduling proposal
- `:flag-track-safety-concern` -- surfaces a track-fault/hazmat-
  handling/derailment-risk concern; ALWAYS escalates
- `:coordinate-maintenance` -- rolling-stock/track maintenance coordination

This actor is explicitly NOT the dispatcher, NOT the track-safety
authority, and NOT rolling-stock/locomotive control. Every proposal it
emits carries a literal `:effect :propose` and an `:action` drawn from
a four-member closed allowlist (`railfreight.governor/allowed-
actions`) -- a proposal to directly finalize a track/dispatch-safety-
authority decision (clearing a train for departure after a reported
track fault, overriding a hazmat-handling protocol) is not merely
disallowed by policy, it cannot be represented in this allowlist at
all. Broader capability-library integration (`kotoba-lang/robotics`
missions/telemetry, `kotoba-lang/logistics` booking/reconciliation, as
named in the pre-existing README `Capability layer` section) remains
an explicit follow-up, not attempted in this R0 slice.

## Decision

### Decision 1: TWO independent layers block any track/dispatch-safety-authority-finalizing proposal

1. **Structural**: `action-allowlist-violations` hard-blocks any
   `:action` outside the four-member `railfreight.governor/allowed-
   actions` set -- a finalizing action literally cannot be
   represented. `op-allowlist-violations` does the same for `:op`.
2. **Textual**: `scope-exclusion-violations` scans the proposal's own
   rationale/summary for a small set of finalization/execution ACTION
   phrases (see Decision 2) -- catches a proposal that merely NAMES a
   forbidden act in its prose even without a matching `:action`.

Both are HARD, permanent, un-overridable blocks -- a human approver
never even sees them (HOLD never reaches `:request-approval`).

### Decision 2: scope-exclusion terms are phrased as ACTIONS, never bare nouns -- a fleet-wide self-tripping bug class, fixed by construction AND by test

Multiple sibling agents in this fleet have independently discovered
and fixed the SAME bug: a governor's own scope-exclusion term list
phrased as a bare noun (e.g. "safety", "dispatch", "clearance")
accidentally matches inside the mock advisor's OWN default rationale/
disclaimer text for a legitimate, allowed proposal -- causing the
actor to self-block on its own happy path. Every disclaimer in
`railfreight.railfreightllm` DENIES having track/dispatch-safety
authority ("does not clear a train for departure", "does not decide
any hazmat-handling protocol") using wording deliberately DIFFERENT
from the full finalization-action phrases in
`railfreight.governor/scope-exclusion-actions` ("clear THIS consist
for departure", "override THE hazmat-handling protocol") -- phrased
as the complete action, not a noun a denial sentence would also
contain. `test/railfreight/governor_self_trip_test.clj` is the actual
guarantee, not wording care alone: it runs the default mock advisor's
`infer` across every op and every seeded consist (including the
hazmat/open-concern/already-open/no-spec-basis branches) and asserts
none of the resulting proposals trip `scope-exclusion-violations`.

### Decision 3: `:effect` is a literal, uniform `:propose` -- a directly-testable structural invariant

Unlike some sibling actors where `:effect` names the concrete SSoT
mutation, this actor's proposal shape separates the two: `:effect` is
ALWAYS the literal keyword `:propose` (asserted by `effect-not-
propose-violations`, HARD/unconditional), and a separate `:action` key
carries the concrete mutation (`:consist/log`/`:consist/mark-
scheduled`/`:consist/flag-safety-concern`/`:consist/mark-maintenance-
coordinated`). This makes "this actor never actuates" a literal,
type-checkable field value rather than an implicit convention.

### Decision 4: "record must be independently verified/registered before ANY action" applies to all three non-registration ops, not only the highest-stakes one

`record-not-verified-violations` gates `:schedule-service-operation`,
`:flag-track-safety-concern`, AND `:coordinate-maintenance` alike on
the subject consist's own `:registered?` fact (set only by a
committed `:log-shipment-record` with a valid spec-basis citation).
This is a stricter reading than some sibling actors' own single-op
"evidence incomplete" gate, but matches this blueprint's own hard
invariant text literally ("a shipment/route record must be
independently verified/registered before any action").

### Decision 5: `:flag-track-safety-concern` always escalates -- TWO independent layers, matching every sibling's real-actuation discipline

`railfreight.governor/high-stakes` includes `:coordination/flag-
track-safety-concern` (confidence/actuation gate always escalates),
AND `railfreight.phase/phases` never includes `:flag-track-safety-
concern` in any phase's `:auto` set (structural). Both `railfreight.
phase-test` and `railfreight.governor-contract-test` assert this
independently. `:schedule-service-operation`/`:coordinate-maintenance`
get the same double-guard, for the same reason this actor coordinates
but never authorizes.

### Decision 6: dedicated double-actuation-guard booleans

`:scheduled?`/`:maintenance-open?` are dedicated booleans on the
`consist` record, never a single `:status` value -- the same
discipline every prior governor's guards establish, informed by
`cloud-itonami-isic-6492`'s real status-lifecycle bug
(ADR-2607071320).

### Decision 7: hand-rolled `enc`/`dec*` EDN-blob codec, not `kotoba-lang/langchain-store`

`kotoba-lang/langchain-store` (ADR-2607141600) is the newer shared
substrate for this codec + identity-schema + entity field-spec
pattern, and is the preferred path for NEW stores. This build instead
mirrors `cloud-itonami-isic-4920`'s own hand-rolled `freightops.store`
exactly, to minimize dependency-resolution risk from combining two
different `langchain`/`langchain-clj` coordinate families on one
classpath (`kotoba-lang/langgraph`'s own transitive `kotoba-lang/
langchain` via `:git/sha`, vs. `langchain-store`'s transitive `com-
junkawasaki/langchain-clj` via `:local/root`) while this actor's own
CI/test path only exercises `-M:test` (no `:dev` override). Migrating
`railfreight.store` to `langchain-store` is a reasonable, low-risk
follow-up once touched again, per this workspace's own "touched,
migrate incrementally" policy -- not attempted here to keep this R0
build on the most-proven path.

### Decision 8: Store protocol, MemStore + DatomicStore parity

`railfreight.store/Store` is implemented by both `MemStore` (atom-
backed, default for dev/tests/demo) and `DatomicStore` (`langchain.
db`-backed), proven to satisfy the same contract in
`test/railfreight/store_contract_test.clj`.

## Alternatives considered

- **Naming the mutation-routing key `:effect` directly** (matching
  `freightops.governor`'s own `:effect :shipment/upsert` style).
  Rejected: this actor's own domain design explicitly states "all
  `:effect :propose`" as a closed-allowlist property of every op, so
  `:effect` is reserved for that literal, uniform value; `:action`
  carries the concrete mutation instead.
- **A single combined `:log-shipment-record`+jurisdiction-assessment
  split into two ops** (matching `freightops`' own separate `:shipment/
  intake`/`:jurisdiction/assess`). Rejected: this actor's closed op
  allowlist is fixed at exactly four members by its own domain design;
  `:log-shipment-record` does both the patch normalization AND the
  spec-basis citation in one proposal.
- **Adopting `kotoba-lang/langchain-store` immediately.** Deferred
  per Decision 7 above -- a reasonable near-term follow-up, not a
  rejection.

## Consequences

- `cloud-itonami-isic-4912` promoted from `:blueprint` to
  `:implemented`, with `:maturity :implemented` added to the
  pre-existing `blueprint.edn` (no other field changed).
- Establishes the closed four-op/four-action allowlist as a literal,
  structurally-enforced (not merely documented) invariant.
- `test/railfreight/governor_self_trip_test.clj` is a dedicated,
  fleet-pattern regression test against the self-tripping scope-
  exclusion bug class -- not just careful wording.
- `MemStore` ‖ `DatomicStore` parity is proven by
  `test/railfreight/store_contract_test.clj`.
- The demo (`clojure -M:dev:run`) walks one clean record-log +
  schedule + maintenance-coordination + concern-flag lifecycle, plus
  seven HARD-hold scenarios (no-spec-basis, unregistered record on two
  different ops, unconfirmed hazmat-handling, an open safety concern
  on two different ops, an already-open maintenance coordination, a
  double-schedule, and a double maintenance-coordination), end-to-end.

## References

- `cloud-itonami-isic-6511/docs/adr/0001-architecture.md` (origin of
  the general governed-actor architecture pattern)
- `cloud-itonami-isic-4920/docs/adr/0001-architecture.md` (nearest
  sibling in the same ISIC-49xx transport family; mirrored closely)
- `kotoba-lang/langchain-store` (ADR-2607141600; deferred adoption,
  see Decision 7)
- This repo's own pre-existing `blueprint.edn`/`README.md`/`docs/
  business-model.md`/`docs/operator-guide.md` (the blueprint this
  build fills in)
