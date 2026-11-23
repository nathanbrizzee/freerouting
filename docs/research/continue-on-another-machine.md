# Continuing on another machine

State at hand-off (2026-11-23): everything that can be committed is committed and pushed. Branch
`router-overhaul` on `origin` (nathanbrizzee/freerouting) is at the head the old machine had; nothing was left
uncommitted in this repository. The branch carries the overhaul described in
`docs/research/router-overhaul-roadmap.md` and `docs/router-overhaul-summary.md`; the one-row-per-change log is
`docs/research/router-overhaul-progress.md`, and the upstream merges are in `docs/research/upstream-merges.md`.

## 1. Get the code and the tools

```bash
git clone https://github.com/nathanbrizzee/freerouting.git
cd freerouting
git remote add upstream https://github.com/freerouting/freerouting.git
git fetch upstream
git switch router-overhaul
python3 scripts/setup/setup.py      # Windows: py scripts\setup\setup.py
./gradlew test                      # fast suite, about 5 minutes
```

`master` stays identical to `upstream/master`; work happens on `router-overhaul`.

## 2. Build the jar and point agents at it

```bash
./gradlew executableJar             # build/libs/freerouting-current-executable.jar
cp build/libs/freerouting-current-executable.jar build/libs/freerouting-local-$(date +%Y%m%d).jar
```

Agents that run Freerouting read the environment variables `FREEROUTING_JAR` (path of the jar),
`FREEROUTING_JAVA` (the `java` executable) and `FREEROUTING_GUI` (`1` to open the window). Set them per user, not
per machine, so there is one place to look. The jar prints its build date on the first log line; a run that shows an
older date is using an old jar. `java -jar <jar> --agent-guide` prints the guide that matches the jar.

## 3. What is not in git on purpose

These live only on the old machine. Copy by hand (a private channel, not git, not a public service) the ones you
need:

- `CLAUDE.local.md` (repository root, excluded from git): the rules for the private boards. Without it a new agent
  does not know them; the short form is: private board files and results stay outside the repository and never go
  into a commit, a pull request or an outside service.
- `docs/research/ups-retest-2026-11-11.md` (excluded): notes from one tester's retest of a private board.
- Claude Code memory, `~/.claude/projects/<project-folder>/memory/` (on Windows
  `C:\Users\<user>\.claude\projects\e--freerouting\memory\`): the folder name encodes the checkout path, so on the
  new machine copy the files into the folder Claude Code creates for that checkout. Today it holds one note:
  entries in `%APPDATA%\freerouting\freerouting.json` (Linux: `~/.config/freerouting/freerouting.json`) silently
  override defaults, so look there before a benchmark.
- `scripts/benchmark/overhaul/boards/` and `runs/` (ignored): benchmark inputs and outputs. Rebuild the inputs as
  `scripts/benchmark/overhaul/README.md` describes (public designs; the KiCad export needs `--with-kicad`). Past
  runs are only needed for comparisons; `scripts/benchmark/baselines/baseline-manifest.json` holds the v2.5.0 baseline.
- The local branch `backup-before-scrub` (never push it) and any local jars. Neither is needed to continue.
- Private board repositories sit in their own repositories and are not part of this one.

## 4. Platform notes

- Shell scripts under `scripts/benchmark/overhaul/` are bash and run as they are on Linux; the PowerShell scripts
  under `scripts/benchmark/` and `scripts/autopilot/` are Windows-oriented (PowerShell 7 also runs on Linux).
- Line endings are LF in the repository (`.gitattributes`); on Windows `core.autocrlf` may be on, which is fine.
- Do not run `spotlessApply` (it reformats every source file). Run `./gradlew spotlessCheck checkstyleMain
  checkstyleTest` and `python scripts/i18n/extract-context.py --check` before handing work off.
- Memory for big boards: 4 GB per JVM worked for the large optimizer runs after the search-tree and optimizer fixes;
  keep at most about four such JVMs at once on a 32 GB machine.

## 5. Where the work stands

- Done and pushed: give-up rules (gain stop, pass and optimizer step caps), the fractured-pour connected-set fix, the
  neck policy with its stub exception (`neck_stub_max_um`), the upstream merge through PR #1134.
- Open, by design: wide-class supply connections that only a narrower trace would close are left to hand routing;
  `plane_as_priority` plus `neck_width_um` helped one board and is not a default (see the summary, section 4).
- Not yet measured: the public board set with the fractured-pour fix and the stub rule. Run it at the next
  milestone as `docs/research/router-overhaul-roadmap.md` describes.
- Open questions: a determinism flake seen once in `PlannedFanoutTest`; the SES import warnings KiCad shows when a
  routed session has items it cannot place.
