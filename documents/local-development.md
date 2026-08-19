# Local Development

## Prerequisites

- JDK 21 for compiling and running the project.
- sbt 2.0.6 runner.
- Docker with Docker Compose support.

## Build Commands

```powershell
sbt clean compile
sbt test
sbt scalafmtSbtCheck scalafmtCheckAll
sbt "scalafixAll --check"
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

Create local `.env` if you need environment overrides.

Windows:

```powershell
.\scripts\windows\init-env.ps1
```

macOS/Linux:

```sh
sh scripts/unix/init-env.sh
```

Both scripts copy `.env.example` to `.env` only when `.env` does not already exist.

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
