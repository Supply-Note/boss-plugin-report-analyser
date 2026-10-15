# GEMINI.md

This repo is a **BOSS dynamic plugin**: Report analyser — Fetch & download reports & summarise

It was scaffolded by the BOSS Tool Creator. The full build instructions live in
the `tool-creator` command (`.gemini/commands/tool-creator.toml`, invocable as
`/tool-creator`). When asked to "follow the tool-creator instructions", read
that file's prompt and follow it step by step.

## Quick reference

- Build: `./gradlew buildPluginJar` (jar lands in `build/libs/`)
- Test locally: copy the jar to `~/.boss/plugins/` (dev-mode hosts use `~/.boss_debug/plugins`)
- `boss-plugin-api` is `compileOnly` — provided by the host at runtime, never bundle it
- Version lives only in `build.gradle.kts`; `plugin.json` is synced at build time
- Compose Multiplatform desktop APIs only; every `PluginContext` provider may be null
- All Kotlin files end with a newline
- Version gates: new SDK-only types → `minApiVersion`; host-rendered capabilities (panel menus, settings pages, shortcuts, status bar, New Tab entries) → `minBossVersion`; feature-detect with `BossApiRuntime.isAtLeast(...)`
- Pushing to `main` releases to the BOSS Plugin Store via `.github/workflows/build.yml`
