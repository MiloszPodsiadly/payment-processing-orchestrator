# Local Development

## Prerequisites

- JDK 21 for compiling and running the project.
- sbt 2.0.6 runner.
- Docker with Docker Compose support.

## Build Commands

```powershell
sbt "clean; compile"
sbt test
sbt "scalafmtSbtCheck; scalafmtCheckAll"
sbt "scalafixAll --check"
sbt verifyArchitecture
sbt integrationTests/Test/compile
```

## IntelliJ / BSP

The repository is an sbt multi-project build. If IntelliJ shows unresolved test symbols
such as `munit.FunSuite` while sbt tests compile, regenerate or reload the sbt/BSP model
from the repository root.

Windows with the local runner:

```powershell
.\.tools\sbt\bin\sbt.bat --server bspConfig
```

After regenerating BSP, reload the sbt project in IntelliJ. The generated `.bsp/`
directory is local-only and intentionally ignored by Git.

## Cassandra

## Environment Variables

The JVM does not read `.env` automatically. Typesafe Config only sees real process
environment variables through `${?PAYMENT_*}` substitutions.

`.env.example` is a committed reference template. The wrapper scripts create `.env`
from `.env.example` when missing, load it for the child command, and do not modify the
parent shell environment.

The wrappers require an explicit command. Running them without a command exits non-zero
and does not print loaded `PAYMENT_*` values.

Windows:

```powershell
.\scripts\windows\run-with-env.ps1 -- sbt "bootstrap/Test/runMain com.paymentprocessing.bootstrap.config.AppConfigEnvironmentProbe"
```

macOS/Linux:

```sh
sh scripts/unix/run-with-env.sh -- sbt "bootstrap/Test/runMain com.paymentprocessing.bootstrap.config.AppConfigEnvironmentProbe"
```

Alternatively, export `PAYMENT_ENVIRONMENT=local` and the other `PAYMENT_*` values in
your shell before launching sbt/application code.

Docker Compose does not consume the `PAYMENT_*` variables in `.env`; Cassandra local
settings are declared directly in `compose.yaml`.

Start local Cassandra:

```powershell
docker compose up -d cassandra
```

Check container health:

```powershell
docker compose ps
docker compose logs cassandra
```

Stop without deleting data:

```powershell
docker compose stop cassandra
```

Delete local Cassandra state:

```powershell
docker compose down -v
```

The cleanup command deletes the named local Cassandra volume.
