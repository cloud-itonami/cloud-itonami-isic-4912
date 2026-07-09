# Contributing

`cloud-itonami-4912` accepts contributions to the OSS blueprint, capability
bindings, policy tests, documentation and operator model.

## Development
The capability layer lives in `kotoba-lang/robotics` and
`kotoba-lang/logistics`. This repo holds the business blueprint and
operator contracts.

```bash
clojure -X:test
clojure -M:lint
```

## Rules
- Do not commit real shipper, consignment or billing data.
- Keep robot dispatch, hazmat approvals and reconciliation records
  behind the Rail Freight Governor.
- Treat dispatch/maintenance workflows as high-risk: add tests for
  robot-safety gating, safety/hazmat scope, evidence, disclosure and
  audit logging.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether operator or certification docs need
updates.
