# Configuration

Configuration is owned by the `bootstrap` module.

The intended flow is:

```text
environment / HOCON
  -> AppConfig.load
  -> validated typed AppConfig
  -> bootstrap wiring
```

## Rules

- No configuration lookup from `domain`.
- No scattered `sys.env` calls.
- No hard-coded secrets.
- Runtime environment must be explicit.
- Invalid critical configuration fails startup before wiring runtime components.
- Unsupported production runtime fails closed in Phase 1.

## Current Categories

- `payment.application`
- `payment.http`
- `payment.cassandra`
- `payment.security`
- `payment.observability`
- `payment.provider`

These are startup categories only. They do not define payment business rules.

## Environment Overrides

See `.env.example` for local environment variable names.

The example values are not secrets and are not safe production credentials.

The JVM does not read `.env` automatically. Values from `.env.example` reach
`AppConfig.load()` only when they are exported into the process environment, for example
through `scripts/windows/run-with-env.ps1` or `scripts/unix/run-with-env.sh`.

`payment.application.environment` accepts only:

- `local`
- `test`
- `production`

Missing, blank, or unknown environment values are rejected. `production` is parsed as a
known environment but is not supported by the Phase 1 runtime and is rejected before
runtime wiring.

`payment.provider.mode` is typed as `ProviderMode`. The only supported value in Phase 1
is `mock`, case-insensitively. Unknown provider modes are rejected; adding a real
provider mode requires actual runtime support in a later phase.
