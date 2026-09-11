# Error-log reporting — schema v1, per the reporting spec

## Delivery rule (as requested)
1. A failure is sent **immediately** to `POST /location/log`.
2. If that call fails, the event is stored in Realm (`PendingLogEntity`, schema 17).
3. At **10** queued events, they ship as one batch (capped at the spec's 50/call).
4. Rows are deleted **only after** the server accepts the batch.
5. The queue also flushes at app start and on every foreground — so crash logs from a dead
   process ship on the next launch.

## What gets logged (spec rule 8: failures, not noise)
| Source | Hook | category |
|---|---|---|
| Every failed API call | `ZillitApi`'s single outcome funnel — no call site can bypass it | `api` (url, method, integer status, request/response truncated at 2 KB, error.message) |
| Socket | rejected handshakes (`socket_connect_failed`), non-deliberate drops (`socket_disconnected`) | `socket` |
| Crashes | `Thread.setDefaultUncaughtExceptionHandler` in `ZillitApplication` — queued synchronously, previous handler still runs | `crash` |
| App transitions | `app_foreground` / `app_background` via ProcessLifecycleOwner | `lifecycle` |

## Spec compliance, item by item (§4 Android checklist)
- **project_id + user_id in the header** — the transport sends `WITH_PROJECT_USER_ID`
  (v2's log header carried device_id only; that is why 0 of its 85k events were findable).
- `api.method`, `api.status` (integer or null — never "N/A"), `error.message`,
  `app_version` — all sent; all were missing from v2.
- Flat `device_model` / `os_version` / `network` — no JSON-inside-a-string.
- `network` is the spec's lowercase set: wifi / cellular / none / unknown.
- `event_time` integer epoch ms. `unique_id` = fresh `UUID.randomUUID()` per event.
- No `moduleData` in the body. Everything platform-specific goes in `context`.
- One `LogEventData` model behind one reporter — a new call site cannot invent a key.

## Loop protection
The transport has its own client path (not `ZillitApi`) and the reporter refuses its own
endpoint — a dead log service queues quietly instead of reporting itself forever.

## Live verification (24 Aug, dev)
The dev location service was **down (502, confirmed with curl)** during testing — which
exercised the hard half for real: immediate sends failed, 30+ events queued durably across
process restarts, every batch attempt retried and kept its rows on failure. The moment the
service returns, the next foreground flush ships the backlog. Delete-on-success is the
same `send()==true` branch and runs then.
