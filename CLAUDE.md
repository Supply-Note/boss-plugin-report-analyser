# CLAUDE.md

This repo is a **BOSS dynamic plugin**: Report analyser — Fetch & download reports & summarise

It was scaffolded by the BOSS Tool Creator. The full build instructions live in
the `tool-creator` skill (`.claude/skills/tool-creator/SKILL.md`) — invoke it
with `/tool-creator` when implementing or changing the tool's behavior.

## Quick reference

- Build: `./gradlew buildPluginJar` (jar lands in `build/libs/`)
- Test locally: copy the jar to `~/.boss/plugins/` (dev-mode hosts use `~/.boss_debug/plugins`)
- `boss-plugin-api` is `compileOnly` — provided by the host at runtime, never bundle it
- Version lives only in `build.gradle.kts`; `plugin.json` is synced at build time
- Compose Multiplatform desktop APIs only; every `PluginContext` provider may be null
- All Kotlin files end with a newline
- Version gates: new SDK-only types → `minApiVersion`; host-rendered capabilities (panel menus, settings pages, shortcuts, status bar, New Tab entries) → `minBossVersion`; feature-detect with `BossApiRuntime.isAtLeast(...)`
- Pushing to `main` releases to the BOSS Plugin Store via `.github/workflows/build.yml`
- PRs get an automatic Claude Code review via `.github/workflows/claude-code-review.yml` (needs the `ANTHROPIC_API_KEY` repo/org secret; skips quietly without it)
