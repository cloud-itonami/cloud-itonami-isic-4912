# cloud-itonami-4912

Open Business Blueprint for **ISIC Rev.5 4912**: freight rail transport
(scheduled/unit-train freight rail carrier operations).

This repository designs a forkable OSS business for community freight
rail transport: safety-management-system and hazmat-transport-scope
management, robotics-assisted track/rolling-stock inspection and
maintenance, and booking/reconciliation records — run by a qualified
operator so a freight rail carrier keeps its own safety-certification
and maintenance history instead of renting a closed rail-operations
platform.

## Scope note: freight rail, not passenger rail or road freight

`cloud-itonami-isic-4911` ("Community Passenger Rail Transport")
already covers scheduled passenger rail service. `cloud-itonami-isic-
4920` ("Community Freight Transport") already covers road freight.
This repository is deliberately scoped to the SEPARATE business of
freight RAIL carriage -- a distinct, independently regulated activity:
freight rail economic regulation is frequently handled by a different
regulator than passenger rail (e.g. the US Surface Transportation
Board's own freight-specific economic oversight, distinct from the
FRA's passenger-focused rules), and freight rail carries its own
hazardous-materials-by-rail transport regime not applicable to
passenger service.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (track inspection,
rolling-stock/wagon maintenance, hazmat-placard verification) operate
under an actor that proposes actions and an independent **Rail Freight
Governor** that gates them. The governor never dispatches a train
service itself; `:high`/`:safety-critical` actions (any service
dispatch outside the operator's own verified safety-management-system
scope, any hazmat consignment outside its verified transport scope,
any maintenance release that has not passed inspection) require human
sign-off.

## Core Contract

```text
intake + identity + safety-management/hazmat scope + booking
        |
        v
Rail Freight Advisor -> Rail Freight Governor -> certificate record, dispatch, reconciliation record, or human approval
        |
        v
robot actions (gated) + service/maintenance record + reconciliation record + audit ledger
```

No automated advice can dispatch a service the governor refuses,
approve a hazmat consignment or maintenance release outside its
verified scope, or publish a reconciliation record without governor
approval and audit evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `4912`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs
- [`kotoba-lang/logistics`](https://github.com/kotoba-lang/logistics) — booking, transit, delivery/reconciliation contracts

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Implementation (R0 + R1)

`src`/`test` implement a NARROWER, explicitly-scoped slice of the
business above: a freight-rail **operations coordination** actor, not
the dispatcher, not the track-safety authority, and not rolling-
stock/locomotive control. RailFreight-LLM (`railfreight.railfreightllm`)
proposes against a closed op allowlist enforced by the independent
Rail Freight Governor (`railfreight.governor`):

- `:log-shipment-record` — consist/cargo-manifest/routing data logging
- `:schedule-service-operation` — train-consist/routing scheduling proposal
- `:flag-track-safety-concern` — surfaces a track-fault/hazmat-handling/
  derailment-risk concern; **always** escalates to human sign-off
- `:coordinate-maintenance` — rolling-stock/track maintenance coordination
- `:register-hazmat-transport-scope` — records operator-supplied evidence
  that a hazmat-handling protocol is on file for a consist; **always**
  escalates. Structural invariant: hazmat transport cannot commit
  without a valid hazmat-transport-scope record.
- `:log-inspection-record` — robotics-assisted track/rolling-stock
  inspection result logging (closed result vocabulary); pure data
  logging, auto-eligible once a record is verified.
- `:release-rolling-stock-from-maintenance` — proposes marking rolling
  stock/track serviceable again; **always** escalates. Structural
  invariant: track/rolling-stock cannot be marked serviceable without
  a completed inspection record present.
- `:log-reconciliation-record` — booking/reconciliation record logging,
  gated on non-blank verified evidence (`docs/business-model.md` Trust
  Control); pure data logging, auto-eligible.

Every proposal carries a literal `:effect :propose` and an `:action`
drawn from a closed allowlist that structurally EXCLUDES any
track/dispatch-safety-authority-finalizing action (clearing a train
for departure, overriding a hazmat-handling protocol, certifying
rolling stock as serviceable) — such an action cannot be represented,
let alone auto-committed. See
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for
the original R0 design record and
[`docs/adr/0002-remove-fabricated-jurisdiction-catalog.md`](docs/adr/0002-remove-fabricated-jurisdiction-catalog.md)
for the R1 correction (a prior `railfreight.facts` catalog asserted
real jurisdictions' official regulator names and legal citations —
removed; `:spec-basis`/`:legal-basis` are now operator-supplied
request keys, never codebase-asserted fact) and business-scope
extension (the three README business areas — safety-management-
system/hazmat-transport-scope management, robotics-assisted
inspection, and booking/reconciliation — that R0 had left as an
explicit follow-up). Broader capability-library integration
(`kotoba-lang/robotics` telemetry, `kotoba-lang/logistics` booking/
reconciliation *contracts*, as distinct from this actor's own minimal
booking/reconciliation record-keeping) remains a follow-up.

```bash
clojure -M:dev:test  # 0 failures, 0 errors (offline: local sibling checkouts)
clojure -M:dev:run    # walk the HARD-hold scenarios + demo lifecycle
clojure -M:lint       # clj-kondo, 0 errors
```

## License

AGPL-3.0-or-later.
