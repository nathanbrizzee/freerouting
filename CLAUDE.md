# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

`AGENTS.md` is the maintainer's long agent-instruction file. It covers settings, DRC, copper pour, analytics, MCP, Docker, jlink and CI invariants, plus PR-review workflow. Read the relevant section of it before touching those areas. `docs/architecture.md` is the authoritative architecture map (it has a Mermaid diagram and a package glossary), and `docs/developer.md` covers contributing. This file only summarises what you need to get started.

## Fork context

This is a fork (`origin` = nathanbrizzee/freerouting, `upstream` = freerouting/freerouting). The `perf-fixes` branch is for applying and testing the performance changes described in `../freerouting-issues.md`. Keep `master` identical to upstream as the unmodified baseline for comparison. The findings were measured against the v2.5.0 release jar built from `b647a2f`, while this clone's master is at `5aa1b24`.

## Commands

Java 25 and Gradle 9 (use the wrapper). Run from the repo root.

```
./gradlew test                 # fast/default unit tests (excludes @Tag("slow"))
./gradlew testSlow             # only slow tests;  ./gradlew testAll = fast + slow
./gradlew check                # full verification incl. integration tests and spotlessCheck
./gradlew test --tests 'app.freerouting.fixtures.Dac2020Bm01RoutingTest'   # quickest routing test, good smoke check
./gradlew executableJar        # -> build/libs/freerouting-current-executable.jar
./gradlew run                  # run from source

# Quality gates to run before handing off work
./gradlew spotlessCheck checkstyleMain checkstyleTest
pre-commit run --all-files
python scripts/i18n/extract-context.py --check     # when Java or translation sources change
```

Do **not** run `spotlessApply`. It reformats every Java source and creates large unrelated diffs. Use `spotlessCheck`, and keep formatting-only changes in a separate change. The repo uses LF line endings (see `.gitattributes`). Do not stage files automatically. Check `git diff --stat` and `git diff --check`.

Headless run of the executable jar: `java -jar build/libs/freerouting-current-executable.jar -de board.dsn -do out.ses --gui.enabled=false`. CLI parameter names are lowercase `snake_case` with dots for nesting (e.g. `--router.plane_as_obstacle=true`).

## Architecture

The main flow is `DSN input -> io.specctra parser -> board/rules model -> autoroute -> drc -> SES output`. The GUI (`gui`), REST API (`api.v1`), MCP server (`api.mcp`) and CLI (`cli`) all sit on top of the shared `management` job scheduler and `core` job/session classes.

- Routing and data logic live in `autoroute`, `board`, `geometry`, `drc`, `core` and `rules`. GUI code is in `gui/**`, REST and MCP code in `api` and `management`, and file-format I/O in `io`.
- These boundaries are enforced by ArchUnit tests in `src/test/java/app/freerouting/architecture/ModuleBoundariesArchTest.java` and `.../io/SpecctraPackageArchTest.java`. Keep them green rather than relaxing the rules. Accepted exceptions are listed in `docs/architecture.md`.
- `HeadlessBoardManager` is the base for API and headless use. `GuiBoardManager` extends it and adds Swing and `WorkspaceSettings`. GUI-only state is reached only through `WorkspaceContract`, never from `api` or `management` code.
- Settings are layered by `SettingsMerger` from nine `SettingsSource`s, from defaults (priority 0) up to API (70). The merge copies a field only if it is non-null and not the Java default, so all fields in `RouterSettings` and its nested classes must be nullable with **no initializers**. Defaults live in `DefaultSettings`. See `docs/settings.md`.

## Routing-change rules (from AGENTS.md)

- Routing must be deterministic, and it must not depend on elapsed time or the remaining time budget. A timeout may only stop work.
- Measure every routing change before and after, on the same boards with the same settings and worker count. Compare unrouted connections, clearance violations (use `DesignRulesChecker.getAllClearanceViolations()`; `BoardStatistics.clearanceViolations.totalCount` is incomplete), completion and time. The baseline is Freerouting v2.5.0 (`scripts/benchmark/baselines/baseline-manifest.json`).
- Write tests for new routing logic. Issue-regression and full-pipeline tests go in `src/test/java/app/freerouting/fixtures/` (extend `RoutingFixtureTest`, DSN files in `fixtures/`). Other tests go in the matching test package. Tag long tests `@Tag("slow")`. For large boards, bound the work with `TestingSettings` setters (`setMaxItems`, `setMaxPasses`, `setJobTimeoutString`).
- Use `FRLogger` for logging. Use the product name "Freerouting" in prose, with no emojis in docs, commits or user-facing text.
- Pass PR and issue bodies with `--body-file`, never inline `--body`. Never merge PRs without explicit user confirmation.
