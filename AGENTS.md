# Repository Guidelines

> Chinese version: [AGENTS.zh.md](AGENTS.zh.md)

## Project Structure & Module Organization

MouseX is a single-module Maven project (`com.mouseremapper:mousex`) that provides Windows-only absolute mouse-button remapping through a JavaFX dashboard.

- `src/main/java/com/mouseremapper/` — all application code (5 classes, no packages below this one)
- `src/main/resources/` — `MouseX.png`, `MouseX.ico`, and `com/mouseremapper/styles.css`
- `pom.xml` — dependencies (JavaFX 21, JNA, Gson) and build plugins
- `package.xml` — Launch4j EXE packaging template
- `target/` — build output (shaded JAR, ~20 MB)
- `profiles.json` — user config, written to the process working directory at runtime (gitignored)
- `readme.md`, `guide.md`, `MouseX.md`, `release-notes/` — user and developer documentation

There is no `src/test` tree; the project currently has no automated tests.

## Build, Test, and Development Commands

```powershell
mvn clean package     # compile + produce target/mousex-1.0.0.jar (shaded fat JAR)
mvn javafx:run        # launch the dashboard via the JavaFX Maven plugin
launch4jc package.xml # wrap the shaded JAR into MouseX.exe (Windows, after mvn package)
```

The shaded JAR manifest points at `com.mouseremapper.Main`, a thin wrapper around `App.main`.

When running from an IDE, launch **`com.mouseremapper.Main`**, not `App`. Running `App` directly fails with `JavaFX runtime components are missing`, because the JVM launcher rejects `Application` subclasses unless JavaFX is on the module path.

## Coding Style & Naming Conventions

- Java 21, 4-space indentation, no tabs.
- `PascalCase` classes, `camelCase` methods/fields, `UPPER_SNAKE_CASE` constants (e.g. `KEY_MAP`, `BUTTON_LABELS`).
- Config/DTO classes use public mutable fields (see `HookManager.RemapConfig`) so Gson can serialize them.
- UI copy and inline style strings live in `App.java`; shared styles belong in `styles.css`.
- No formatter or linter is configured — match surrounding code.

## Commit & Pull Request Guidelines

History mixes Conventional Commits (`feat(config):`, `docs(readme):`, `clean(config):`) with plain imperative subjects. Prefer `type(scope): summary` in the imperative mood.

Pull requests should describe the behavior change, note the Windows/macOS impact, and include a screenshot for any dashboard or layout change. Link related issues and update `readme.md`/`guide.md` when user-facing behavior changes.

## Platform & Configuration Notes

MouseX is **Windows-only**: hooks (`WH_MOUSE_LL`), `keybd_event`, registry autostart, and `EnumWindows` all use JNA Win32 APIs. The UI may start on macOS, but `START HOOK` fails. Never commit `profiles.json`, `config.json`, `MouseX.xml`, or `*.exe`.
