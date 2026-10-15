# Report analyser

Fetch & download reports & summarise

A [BOSS](https://github.com/risa-labs-inc/BossConsole) dynamic plugin, scaffolded
by the BOSS Tool Creator.

## Build

```bash
./gradlew buildPluginJar    # output: build/libs/boss-plugin-report-analyser-<version>.jar
```

Copy the jar to `~/.boss/plugins/` and reload plugins in BOSS to test locally.

## Requested permissions

- `files.read` — Read files and directories in the user workspace
- `network.access` — Make outbound HTTP or socket connections
- `mcp.tools` — Offer tools to AI agents via the boss MCP server
- `browser.control` — Open and drive browser tabs in BOSS
- `secrets.read` — Access the BOSS secret manager

## Release

Pushing to `main` builds the jar, creates a GitHub release, and publishes to the
BOSS Plugin Store (requires the `BOSS_STORE_PLUGIN_PUBLISH_KEY` repo secret).
