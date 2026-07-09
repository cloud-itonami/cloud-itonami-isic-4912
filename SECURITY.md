# Security Policy

This project handles safety-management-system, hazmat-transport-scope
and reconciliation-record workflows. Treat vulnerabilities as
potentially high impact even when the demo data is synthetic.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- safety-management-system/hazmat credential exposure
- real shipper or consignment data exposure
- authorization bypass
- Rail Freight Governor bypass
- audit-ledger tampering
- over-disclosure in reconciliation records or exports
- tenant isolation failures

## Reporting

Use GitHub private vulnerability reporting when available for the repository.
If that is unavailable, contact the repository maintainers through the
cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on shipper/consignment data, policy enforcement or audit
  logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real shipper and consignment data outside this repository.
- Run policy tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for operators and service accounts.
