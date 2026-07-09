# Business Model: Community Freight Rail Transport

## Classification
- Repository: `cloud-itonami-4912`
- ISIC Rev.5: `4912` — freight rail transport
- Social impact: rail safety, supply-chain resilience, decarbonization
  of freight movement

## Customer
- independent/community freight rail carriers needing an auditable
  safety-management and hazmat-scope platform
- shippers needing verifiable consignment, transit and delivery
  records for rail-carried freight
- regulators needing verifiable safety-management-system, hazmat-
  scope and maintenance records
- programs that cannot accept closed, unauditable freight-rail
  operations platforms

## Offer
- safety-management-system and hazmat-transport-scope management
- robotics-assisted track and rolling-stock/wagon inspection and
  maintenance
- consignment booking, transit and delivery dispatch records
- shipper reconciliation and billing records
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per yard/corridor
- support retainer with SLA
- track/rolling-stock inspection robot integration and maintenance

## Trust Controls
- a robot action the governor refuses is never dispatched
- safety-critical actions (service dispatch outside verified safety-
  management-system scope, hazmat consignment outside verified
  transport scope, maintenance release without inspection) require
  human sign-off
- freight cannot be dispatched outside its verified safety and hazmat
  scope
- reconciliation records require verified evidence
- sensitive shipper and consignment data stays outside Git
