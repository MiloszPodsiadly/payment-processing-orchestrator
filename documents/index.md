# Project Documents Index

This directory is reserved for lightweight project notes that are separate from the architectural prompt sources in `docs/`.

## Entries

- `phase-0-architecture-charter.md` - Phase 0 charter, scope, vocabulary, invariants, state-machine outline, failure model, ADR skeleton, and gate answers.
- `module-dependency-map.md` - Phase 1 module responsibilities, allowed dependencies, forbidden dependencies, and `verifyArchitecture` enforcement notes.
- `domain-model.md` - Phase 2 pure domain model, typed values, decision model, duplicate semantics, replay integrity, unknown outcomes, diagnostics, and implemented invariants.
- `payment-state-machine.md` - Phase 2 implemented payment states, legal commands, illegal commands, operation identity, unknown states, replay validation, and corrupt-history behavior.
- `configuration.md` - Configuration ownership, fail-closed runtime environment rules, and typed startup loading rules.
- `runtime-pekko-payment-entity.md` - Phase 3 PaymentEntity runtime boundary, reply semantics, persistence identity, recovery semantics, and explicit Phase 4 exclusions.
- `testing-workflow.md` - Test categories, sbt 2 command syntax, architecture checks, current test coverage, and deferred test work.
- `local-development.md` - Prerequisites, build commands, truthful `.env` wrapper semantics, and local Cassandra workflow.
- `adr/index.md` - Initial ADR index expanded from the Phase 0 accepted direction.
