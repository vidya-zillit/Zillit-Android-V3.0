# Zillit Android v3

A ground-up rewrite of the Zillit production-management app in Jetpack Compose.

The package name (`com.zillit.zillitapp`) and the server contract are shared with v2, but no
code is: v2 is a reference for *what* each screen does, not a source to port from.

## Getting a clone to build

`local.properties` is not committed — it holds every base URL and API key the build injects
into `BuildConfig`. Copy the file from another machine, or ask for it. It needs the Android
SDK path plus ~100 keys, of which the ones most likely to be missed are:

| Key | Used for |
|---|---|
| `sdk.dir` | Android SDK location |
| `{PROD,STG,QA}_*_BASE_URL` | one set of service URLs per flavour |
| `MAPS_API_KEY` | the shared location picker |
| `AGORA_APP_ID` | calling |
| `DOCS_BASE_URL` | the "More" link in every ⓘ dialog |
| `VIDEO_BASE_URL` | the tutorial videos those dialogs play |

Missing keys resolve to `""` rather than failing the sync, so a wrong-looking runtime error
(a blank map, a dead link) usually means a missing key rather than broken code.

Flavours: `develop` (installs alongside v2 as `.stg`), `qa` and `prod` — both of which reuse
v2's application ids and therefore **replace** v2 on a device.

## Where things live

| Path | Contents |
|---|---|
| `core/` | Everything shared: network, Realm, socket, badges, storage, and the UI vocabulary in `core/ui/components` |
| `feature/` | One package per screen area, each with its own ViewModels |
| `navigation/` | Type-safe routes and the single `NavHost` |

The rule the codebase is held to: **same UI and same behaviour means one component in
`core/`, never a per-feature copy.**

## Working notes

Design and parity decisions are written down as they are made, rather than living in commit
messages:

- [`CALENDAR_IMPLEMENTATION_CHECKLIST.md`](CALENDAR_IMPLEMENTATION_CHECKLIST.md) — the
  calendar module, phase by phase, including every wire-format fact discovered against the
  live API and the bugs found while verifying on a device.
- [`SETTINGS_MODULE_AUDIT.md`](SETTINGS_MODULE_AUDIT.md) — what v2's settings page does, what
  v3 changed, and the badge and role rules behind it.
