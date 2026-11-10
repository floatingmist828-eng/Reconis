# Reconis backend

Spring Boot and MyBatis services for campus payments, reconciliation and case handling.

- `identity` and `merchant`: accounts, roles, merchant membership and stores.
- `order` and `payment`: orders, payment confirmation, refunds and channel notifications.
- `reconciliation` and `casework`: statement imports, matching, scheduled tasks and independent review.
- `analytics` and `common`: statistics, exports, file access and shared infrastructure.

Application code is in `src/main/java/cn/reconis`; tests are in `src/test`.
Use the repository build script to package the backend with the frontend.
See the [API guide](../docs/api.md) and [runbook](../docs/runbook.md) for authentication, configuration and database tests.
