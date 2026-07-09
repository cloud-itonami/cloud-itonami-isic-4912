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

## License

AGPL-3.0-or-later.
