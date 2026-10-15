# Report analyser

Fetch & download reports & summarise

A [BOSS](https://github.com/risa-labs-inc/BossConsole) dynamic plugin, scaffolded
by the BOSS Tool Creator.

## What it does

**SupplyNote mode** (SupplyNote Data API v2, `https://api.supplynote.in`):

- Fetches any of the 10 insights (`/v2/insights/*`) or 21 datasets (`/v2/data/*`), following
  cursor/offset paging up to a row cap, and honouring `Retry-After` on 429.
- Enforces the guide's date rules (e.g. `inventory-log` needs a range, `current-stock` refuses one).
- Runs read-only custom SQL (`POST /v2/sql`) and fetches feeds (`/v2/feeds/{id}/data`).
- **Download CSV** requests a dataset export (`export=csv`) and opens the time-limited link in the
  BOSS browser, so the file lands in the BOSS download manager.
- Summaries include `meta.units`, warnings and definitions, and never total quantities, prices or
  percentages.

**URL / File mode**: analyse any http(s) URL (optionally authenticated with a BOSS secret) or local
file — CSV, TSV, JSON, Excel `.xlsx`, HTML tables, text, and best-effort PDF text. Completed BOSS
downloads are listed with a one-click **Analyse**.

Every report gets an offline statistical summary (column types, ranges, totals, data quality) and an
optional **AI summary** through the BOSS AI gateway (whatever provider is configured in BOSS).

### Setting up the SupplyNote key

Create a secret in BOSS Secrets with website `api.supplynote.in` and the API key as the password.
The plugin picks it automatically and only ever sends it to that host, as `X-API-Key`.

### MCP tools (for AI agents)

| Tool | Purpose |
|------|---------|
| `reportanalyser_supplynote_catalog` | List insights/datasets with date rules and caveats |
| `reportanalyser_supplynote_fetch` | Fetch + analyse an insight/dataset (optional AI summary) |
| `reportanalyser_supplynote_sql` | Run a SELECT against `sn_public.*` |
| `reportanalyser_supplynote_export` | Get a dataset export link and download it in BOSS |
| `reportanalyser_supplynote_feeds` | List feeds or analyse one |
| `reportanalyser_analyse_source` | Analyse a URL or local file |
| `reportanalyser_list` / `reportanalyser_get` / `reportanalyser_summarise` | Browse, read and summarise analysed reports |

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

The plugin never writes files itself: downloads go through the BOSS browser's download manager.

## Release

Pushing to `main` builds the jar, creates a GitHub release, and publishes to the
BOSS Plugin Store (requires the `BOSS_STORE_PLUGIN_PUBLISH_KEY` repo secret).
