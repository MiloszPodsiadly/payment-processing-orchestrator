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
- Local defaults are allowed only for local development.
- Invalid critical configuration fails startup before wiring runtime components.

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

