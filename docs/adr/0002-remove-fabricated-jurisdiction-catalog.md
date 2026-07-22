# ADR-0002: remove fabricated jurisdiction-regulatory catalog; close the three README business-scope gaps

## Status

Accepted. Follow-up to ADR-0001, landed in the same PR cycle as the
initial R0 build (no separate merge to `main` occurred in between).

## Context

ADR-0001 promoted `cloud-itonami-isic-4912` from `:blueprint` to
`:implemented` with a four-op RailFreight-LLM ⊣ Rail Freight Governor
actor. Two problems surfaced on review against this repo's own build
brief before that promotion could stand as delivered:

1. **Fabrication.** `railfreight.facts` shipped a hardcoded `catalog`
   asserting REAL jurisdictions' official rail-safety regulator names,
   specific legal citations (Japan's 鉄道事業法, US 49 C.F.R. Parts
   200-299, UK ROGS 2006, Germany's AEG), and government URLs, as if
   this codebase itself were an authoritative source of regulatory
   fact. `railfreight.railfreightllm/propose-log-shipment-record`
   looked citations up there instead of taking them from the operator.
   This actor is an INTERNAL OPERATIONS COORDINATION actor, not a
   jurisdiction-facts actor, and its own design brief is explicit that
   it must never assert real-world regulatory content it cannot
   verify. `railfreight.governor/spec-basis-violations` was already
   correctly structural (it only ever checked the PROPOSAL's own
   `:cites`/`:spec-basis` for presence, never consulting the catalog
   directly) — the fabrication was confined to `railfreight.facts` and
   the advisor's consumption of it, plus the seeded demo/test
   citations in `railfreight.store/demo-data`.

2. **Scope gap.** ADR-0001's own "Scope decision" section explicitly
   named safety-management-system/hazmat-transport-scope management,
   robotics-assisted inspection, and booking/reconciliation records as
   "an explicit follow-up, not attempted in this R0 slice" — but
   `docs/business-model.md`'s own Trust Controls literally require
   two of the checks this repo's own build brief calls out by name:
   "hazmat transport cannot commit without a valid hazmat-transport-
   scope record" and "track/rolling-stock cannot be marked serviceable
   without a completed inspection record present", plus "reconciliation
   records require verified evidence". None of the R0 build's four ops
   could represent any of these.

## Decision

### Decision 1 (correction): `railfreight.facts` no longer asserts jurisdiction-regulatory content

The jurisdiction `catalog` (owner-authority/legal-basis/provenance/
required-evidence per ISO3, plus a parallel hazmat-by-rail catalog) is
removed entirely. `:log-shipment-record` requests now carry their own
OPERATOR-SUPPLIED `:spec-basis`/`:legal-basis` strings — opaque to
this actor, never validated against a hardcoded "official" list. An
absent `:spec-basis` still means the proposal has none, and
`spec-basis-violations` still HOLDs it — this check's own logic did
not change, only where the citation comes from. `railfreight.store`'s
demo/test fixtures now use clearly-labeled
`operator-submitted-sms-registration-JPN-NNNN`-style placeholders
instead of real government URLs and statute names.

What remains in `railfreight.facts` is exactly what this actor's build
brief allows: a closed, non-regulatory vocabulary internal to this
actor's own record-keeping (`inspection-results`), never a citation of
an external standard.

### Decision 2 (extension): three new ops close the README business-scope gap, using the SAME two-independent-layer discipline as the original four

- `:register-hazmat-transport-scope` — `railfreight.governor/hazmat-
  scope-evidence-violations` HARD-blocks a proposal with no non-blank
  operator-supplied `:evidence`. Committing sets
  `:hazmat-handling-confirmed?`/`:hazmat-handling-evidence` on the
  consist — the ONLY way those facts can ever become true (previously
  `:hazmat-handling-confirmed?` was seeded `false` and had no
  committable path to `true` at all). `:schedule-service-operation`'s
  existing `hazmat-handling-protocol-violations` check is unchanged in
  logic, now genuinely satisfiable.
- `:log-inspection-record` — `railfreight.governor/inspection-record-
  invalid-violations` HARD-blocks an `:inspection-result` outside the
  closed `railfreight.facts/inspection-results` vocabulary. Pure data
  logging (an observation, not an authorization) — auto-eligible in
  phase 3 once the record is verified, same discipline as
  `:log-shipment-record`.
- `:release-rolling-stock-from-maintenance` — `railfreight.governor/
  maintenance-not-open-violations` HARD-blocks a release with no open
  maintenance-coordination request; `maintenance-release-uninspected-
  violations` HARD-blocks a release with no completed inspection
  record on file for the consist (`:last-inspection-result` nil) —
  this repo's own build brief, verbatim: "track/rolling-stock cannot
  be marked serviceable without a completed inspection record
  present". `open-safety-concern-violations` is extended to cover this
  op too (an unresolved track-safety concern must block a maintenance
  release, not only scheduling/coordination).
- `:log-reconciliation-record` — `railfreight.governor/reconciliation-
  evidence-violations` HARD-blocks a record with no non-blank
  `:evidence` — `docs/business-model.md`'s own Trust Control, verbatim:
  "reconciliation records require verified evidence". Pure data
  logging — auto-eligible in phase 3.

All three new ops are added to `record-not-verified-violations`'s
gated-op set (the subject consist's own shipment/route record must
already be verified/registered before ANY of them, matching ADR-0001
Decision 4's reading of the README's own invariant), and to the closed
`allowed-ops`/`allowed-actions` allowlists. `:register-hazmat-
transport-scope` and `:release-rolling-stock-from-maintenance` are
added to `railfreight.governor/high-stakes` and are absent from every
phase's `:auto` set in `railfreight.phase` — the same TWO independent
layers (governor high-stakes + phase auto-exclusion) ADR-0001 Decision
5 established for `:flag-track-safety-concern`, now covering five ops
instead of three. `:log-inspection-record`/`:log-reconciliation-
record` join `:log-shipment-record` as the phase-3 `auto-eligible-ops`
set (pure data logging, no operational/safety authorization weight of
their own) — `phase-3-auto-commits-only-no-operational-risk-ops`
(`test/railfreight/phase_test.clj`) now asserts this three-member set
instead of the original one-member set.

### Decision 3: CI added

This repo had `deps.edn`/`src`/`test` but no
`.github/workflows/ci.yml` at all. Added, mirroring the sibling-
checkout pattern (`cloud-itonami-isic-869`/`-0115`/`-4920`): the test
job checks out `kotoba-lang/langgraph` and `kotoba-lang/langchain`
(this repo's own `deps.edn`, unchanged by this ADR, deliberately does
NOT also depend on `kotoba-lang/langchain-store` — see ADR-0001
Decision 7 — so that sibling is not checked out).

## Alternatives considered

- **Keep the jurisdiction catalog but strip only the specific
  citations, replacing them with generic-but-still-hardcoded
  placeholder "official" entries.** Rejected: any codebase-level
  catalog claiming to know what's "official" for a jurisdiction is the
  actual failure mode, independent of whether today's placeholder text
  happens to look real — the fix has to move the knowledge to the
  operator, not just re-word it.
- **Leave the R0 four-op scope as-is and treat the README's broader
  business areas as a permanent, unscoped-in-code aspiration.**
  Rejected: this repo's own build brief names two of the missing
  structural checks by name (hazmat-transport-scope record presence,
  inspection-record-before-serviceable), and `docs/business-model.md`
  already commits to "reconciliation records require verified
  evidence" as a Trust Control — these are not speculative additions,
  they are already-promised behavior this repo had not yet delivered.

## Consequences

- `railfreight.facts` no longer requires updating as real-world
  regulations change, and can never drift out of sync with them,
  because it no longer asserts any.
- The closed op allowlist grows from four to eight members; the
  closed action allowlist grows from four to eight members
  (1:1, unchanged discipline from ADR-0001 Decision 3).
- `test/railfreight/facts_test.clj` is rewritten (the old catalog-
  presence assertions are gone; new assertions cover the closed
  `inspection-results` vocabulary). `test/railfreight/governor_
  contract_test.clj` gains dedicated HARD-hold + happy-path coverage
  for all three new ops, mirroring the existing coverage style for the
  original four. `test/railfreight/governor_self_trip_test.clj`'s
  regression guarantee is extended to run across all eight ops, not
  only the original four. `test/railfreight/store_contract_test.clj`
  gains MemStore ‖ DatomicStore write-parity coverage for the four new
  commit actions.
- `blueprint.edn`'s `:implemented-slice` is updated to describe this
  corrected, extended R1 slice rather than the original R0 four-op
  slice.

## References

- ADR-0001 (this repo) — original architecture, R0 scope decision
- `docs/business-model.md` Trust Controls, `docs/operator-guide.md`
  Minimum Production Controls — the source of the two structural
  checks and the evidence requirement this ADR implements
- `cloud-itonami-isic-4920/docs/adr/0001-architecture.md` — nearest
  sibling in the same ISIC-49xx transport family
