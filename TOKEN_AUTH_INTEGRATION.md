# Bearer-token auth — API + socket, per the Aug 2026 spec mails

Both mails implemented end to end, live-verified against dev (flag on, 15-min TTL).

## Sep 2026 change — the mode is decided by `POST /session/device`, not a flag

`token_auth_enabled` was **dropped from `GET /configuration`** (it is project-scoped; the token
flow starts at device level, before any project is chosen). v3 kept waiting for it and so
stayed on moduledata for every call — and routes that have since tightened (mail, for one)
refused those calls with errors that read like data problems. v2 already follows the new
rule; v3 now matches it (`TokenSession`, 15 Sep 2026):

- Token mode is **on by default**. `POST /session/device` at app start is the probe.
- `401 libs_invalid_device_id` → no device record yet (fresh install, before create/join).
  Stay on moduledata; re-probe when a project opens, since create/join registers the device.
- `session_token_auth_disabled` → kill-switch, moduledata for the session. **Only** that
  explicit verdict — a bare 503 is a load balancer hiccup, and treating it as the kill-switch
  parked whole sessions on moduledata.
- A token-eligible call **suspends** until the session exists (`bearerTokenFor`) and falls
  back to moduledata only after acquisition genuinely failed. An eager fallback is what
  `libs_moduledata_not_accepted` looks like.
- Project variants with **no project open** ride the device token, not moduledata.
- `WITH_PROJECT_USER_BUCKET_DATA` rides the bearer: in v3 it fronts ordinary CRUD routes
  (the S3 transfer itself goes through the SDK). Scanner, Box and pre-registration variants
  stay legacy — they feed the only routes that read non-identity header fields.
- A dead refresh token re-establishes via moduledata rather than logging anyone out.

Live-verified on the fold emulator: `device → 200`, `project → 200 scope=member`, every call
on a bearer with no moduledata block.

## The pieces

| File | Role |
|---|---|
| `core/auth/TokenModels.kt` | Wire shapes, `AccessToken` with skewed expiry, local JWT claim decoding, the error-message catalogue |
| `core/auth/SecureTokenStore.kt` | The refresh token, AES-GCM-sealed with an **Android Keystore** key, ciphertext in DataStore. Access tokens never touch disk |
| `core/auth/SessionApi.kt` | The three session endpoints, on a direct client path so a refresh can never recurse through the 401 handler that calls it |
| `core/auth/TokenSession.kt` | The session: flag, per-project token map, **single-flight refresh**, proactive renewal at 80% of `expires_in`, bootstrap-from-refresh at launch, `sessionLost` for the future login screen |
| `ZillitApi` | Attaches `Authorization: Bearer` in place of moduledata; 401 → refresh → retry **once**; `libs_invalid_token_scope` → re-mint the project token; 503 kill-switch → flag off locally + moduledata retry |
| `SocketManager` | Handshake credential chosen **at every attempt** (`token` vs `moduledata`); `connect_error: libs_invalid_token` → single-flight refresh → reconnect; the retry loop refreshes a dead token before each attempt |

## Where the spec's rules land

- **Never hardcode the TTL** — everything schedules off `expires_in`; nothing knows about hours.
- **Single-flight** — one shared `Deferred` in `TokenSession.refresh()`; the API 401 path, the socket rejection, the reconnect loop and the proactive timer all funnel through it, so concurrent failures rotate exactly once.
- **Rotation discipline** — the new refresh token is written to the Keystore-sealed store before anything else can run; the old one is never re-presented.
- **401 ≠ logout** — v2's rule is gone; logout is only for a failed refresh (`sessionLost`, consumed when a login screen exists).
- **Rule 2 (socket)** — nothing watches expiry on a live socket; the fresh token is simply what the next natural reconnect presents.
- **Coexistence** — everything falls back to moduledata: flag off, no token yet, kill-switch, or a variant tokens cannot express.

## One deliberate narrowing

The token carries exactly `device_id / project_id / user_id`. Three moduledata variants carry **more** (upload bucket credentials, the QR `scanner_device_id`, Box ids, the calling identity) — swapping those for a Bearer would silently drop fields the server reads. `ModuleData.tokenEligible` therefore limits substitution to `DEFAULT`, `WITH_PROJECT_ID`, `WITH_PROJECT_USER_ID`; the rest stay on moduledata until the backend owns those fields elsewhere. **This is also why "remove moduledata entirely" cannot happen yet** — beyond the spec's own rule that `POST /session/device`, create-project and join-project still send it.

## Live verification on dev (24 Aug)

- `token_auth_enabled:true` read from `/configuration`; flag catch-up mints for the already-open project (the flag lands *after* project open — establishing at open alone silently did nothing; fixed).
- `POST /session/device` → 200 → `POST /session/project` → 200, `scope=member`.
- Post-mint API calls carry Bearer, **no moduledata**, HTTP 200 — including against the *notification* service, so the shared-library verification is cross-service.
- Cold restart: `refresh → 200` (stored token rotated) → `project → 200` — **no login**, per section 6.
- Network blip → reconnect presented **the token** → `JOINED (ack)` (Rule 1 live).
- Dev flapped mid-test (socket `xhr poll error`, refresh 502): the app stayed on moduledata and recovered — the coexistence net works under real failure.
- 15-min TTL: proactive-refresh timer verification running (fires ~12 min).

## Still open

- `call:*` payload `user_id` — calling is not built in v3 yet; requirement recorded on `SocketEvents` so it cannot be missed.
- `sessionLost` → logout wiring — no login screen exists yet.
- QA/prod flags stay off until the platforms confirm; nothing here activates without the server flag.
