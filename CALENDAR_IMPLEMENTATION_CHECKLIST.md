# New Calendar — v3 implementation checklist

Built from a file-by-file read of v2's **`bottomNav/new_calendar`** (114 files, 16,274 lines).
The old `bottomNav/calendar` package is **not** a reference — v2 ships two calendars and only
`new_calendar` is current.

The calendar is a Home **unit** but not a chat unit: no composer, no emoji, no attachment,
no send, no voice note. It replaces the chat surface for `home_unit_calendar`.

Status: `[x]` done · `[ ]` not started · `[~]` in progress

---

## Phase 1 — Foundation

- [x] `CALENDAR_BASE_URL` build field for all three flavours (values already in `local.properties`)
- [x] `ApiEndpoints.Calendar` — 14 routes
- [x] Domain models — `CalendarEvent`, `EventType`, `CallType`, `EditScope`,
      `InvitationStatus`, `RecurrenceFrequency`, `RecurrenceRule`, `Invitation`
- [x] DTOs + mapping, including the server-computed `_isVirtual` / `_isCancelled` /
      `_isDeclined` / `_isDeleted` flags
- [x] `CalendarRepository` — window fetch, per-event fetch, invites, created, declined,
      join-call, accept, reject, delete
- [x] `ProjectUser` for the invitee picker — **reused `ProjectDirectory`, no calendar copy**.
      v2's `calendar/project-users` endpoint is a dead stub; its own picker reads Realm.
- [x] Departments — added to `ProjectDirectory` (`ProjectDepartmentEntity`, schema 12,
      fetched in the project-open fan-out), *not* to the calendar, so every module shares it
- [x] `TimezoneDto` + timezone list *(v2: `dto/TimezoneDto.kt`)*
- [x] `OccurrenceStatuses` model + `InvitationStatusResolver` *(v2: `dto/OccurrenceStatusesDto.kt`,
      `domain/util/InvitationStatusResolver.kt`)*
- [x] `InvitationListResponse` + `CalendarRepository.eventInvitations()` — backs the Phase 4
      attendee list, which the capped `invitations` on an event response cannot

## Phase 2 — Main calendar screen

*v2: `ui/calendar/CalendarFragment` + `CalendarViewModel` + 3 adapters,
`new_calendar_fragment_calendar.xml`*

- [x] Route `HomeUnitKind.CALENDAR` away from `ChatThread` — **no composer at all**
- [x] Header: month title, prev / Today / next, view-mode switch.
      **No settings gear and no user switcher** — both are `GONE` in v2, the host owns them.
- [x] Month grid with per-day event counts *(v2: `MonthGridAdapter`)*
- [x] Week and Day modes *(v2: `ViewMode { MONTH, WEEK, DAY }`)*
- [x] Hourly timeline for Day mode, opening near the current hour *(v2: `HourlyTimelineAdapter`)*
- [x] Day card — big day number, weekday, month, TODAY tag
- [x] Agenda list for the selected day, with the `Today's Events` / `Tomorrow's Events` /
      date heading *(v2: `DayAgendaAdapter`)*
- [x] Cancelled + declined rows struck through and faded rather than hidden
- [x] Quick actions: **Events** (badge + Received/Created/Declined menu) and **Join Call**.
      Only the Received row carries a count — see the note below.
- [x] FAB → create event, carrying the selected date
- [x] Pull to refresh, including on an empty day
- [x] Empty state — `No events for this day`
- [x] Quick-action and FAB destinations wired *(Phases 3 & 5)*
- [x] `eventsBadgeCount` supplied by the badge derivation *(Phase 7)*
- [x] Settings gear added to the header — v2 hides its own because the host owns those
      settings elsewhere; v3 has nowhere else for them

Verified on the emulator against the live dev API: month/week/day, day selection, empty
day, cancelled-event rendering, the Events menu.

## Phase 3 — Event lists

*Each is a screen + ViewModel + adapter in v2; all four share `EventCardAdapter`.*

**All four are one screen + one ViewModel**, selected by a route argument — see
`EventListKind`. v2 built four fragments, four ViewModels and two card adapters, which is
why search and filtering behaved differently on each.

- [x] Received invitations — Pending / Accepted / Expired *(`ui/received`)*
- [x] Created events — Members / Personal / Expired *(`ui/created`)*
- [x] Declined — Received *(invitations I declined)* / Created *(my events others declined)*
- [x] Join Call — today only, no tabs, local search *(`ui/joincall`)*
- [x] Cursor paging, server-side search, Today + date filters, pull to refresh, empty state
- [x] Shared event card: colour dot, title, date/time, expired + excluded tags, status chip,
      invitee count + See Invitees, recurrence badge with end date, location, edit/delete
      *(v2: `EventCardAdapter`, `new_calendar_item_event_card.xml`)*
- [x] Card gating rules carried over exactly: nothing actionable when expired or cancelled;
      accept hides once accepted and reject once rejected; Join Call follows the call type
      but never shows to someone who declined; edit/delete withheld from box-schedule events
- [x] Local delete suppression, so a deleted row cannot reappear on the next page fetch
- [x] Tab badge counts *(Phase 7)*
- [x] Inline occurrence expansion on a recurring card *(Phase 4)*
- [x] Decliner details on the Declined → Created tab *(see Phase 4)*
- [x] The card's actions wired through to the detail sheet, form and delete flow

Verified on the emulator against the live dev API: all four endpoints, tab switching,
paged decoding, and real cards on Created → Personal.

## Phase 4 — Event detail + invitation actions

- [x] Detail bottom sheet *(v2: `ui/detail/EventDetailBottomSheet` — 576 lines)* — title,
      type, time + duration, date + week of year, location as a maps link, call type with
      Join Call, reminder, description, colour, invitee summary, edit/delete for the
      creator or an admin
- [x] Accept / decline as a **sheet**, not a dialog — v2 moved to one because the keyboard
      pushed the buttons off an alert dialog when typing a decline reason
- [x] Scope prompt on a recurring event: this / this and following / all
- [x] Bottom bar states: pending, you accepted, you declined, cancelled, expired
- [x] Per-occurrence accept/reject for recurring series *(v2: `OccurrenceLoader`,
      `occurrence-statuses` endpoint)* — always addressed via the **master** id plus the
      occurrence date, never the occurrence's own id
- [x] Inline occurrence expansion on a recurring card *(carried over from Phase 3)* — lazily
      loaded, cached across collapse, each date with its own status chip. Matching runs
      against the whole `_sibling_ids` chain, or the list stops at a "this and following" split
- [x] Invitee list with per-person status *(v2: `InviteeListDialog`, `CollaboratorListAdapter`)*
      — fetched from `/invitationlist` with debounced server-side search, since an event
      response caps its embedded invitations
- [x] Decliner details on the Declined → Created tab: an expandable list of who declined,
      their reason, and — for a series declined date by date — each date with its own
      reason *(v2: `DeclinedEventCardAdapter`, `decliners` / `distinct_decliner_count`)*.
      Reads both generations of field names: `reason` / `occurrence_dates` and
      `rejection_reason` / `rejected_from_dates`.
- [ ] **"Request reason"** — the action exists on the row but is unwired, because it is not
      a calendar call at all: v2 sends the decliner a **1:1 C&C chat**. v3 has no Chat &
      Calling module yet, so there is nothing to send it through.
- [x] Status resolution rules — `InvitationStatusResolver` *(done in Phase 1; an occurrence's
      status overrides the series', compared in UTC. `resolveForInvitation` deliberately
      ignores the occurrence-statuses payload — it belongs to the caller, and v2 painted one
      user's answers onto every attendee by feeding it in)*
- [x] Join Call surfaced on the card and in the sheet *(v2: `CallType.isJoinable`)* — the
      action itself hands off to the calling module, which v3 has not built yet

Verified on the emulator: the sheet loads `event/{id}` **and** `occurrence-statuses`, shows
the right occurrence when opened from a list row, renders the cancelled banner instead of
actions, and expands a series into its dates.

**Not exercised end to end:** accept and decline. The test account created every event on
the dev project, so it has no invitation to answer — the requests and UI are built and
wired, but the round trip is unproven.

## Phase 5 — Event form (create / edit)

*v2: `ui/form/EventFormFragment` (736 lines) + `EventFormViewModel` (511)*

- [x] Title, description, all-day toggle, start/end date+time
- [x] Timezone picker, searchable, from `/timezones` *(`TimezonePickerFragment`)*
- [x] Event type — members vs personal. Switching to personal **clears** invitees rather
      than hiding them, so they cannot be silently re-sent
- [x] Call type — audio / video / in person / in person + call
- [x] Location description
- [x] Colour picker — the 12 v2 swatches with their names
- [x] Reminder (`notify`)
- [x] Invitee picker — project users, searchable, select-all over the **filtered** set,
      self and creator excluded. Reads `ProjectDirectory` *(the calendar's own
      project-users endpoint is a dead stub in v2)*. Designations resolve through the
      label dictionary — the server stores them as `director_label`.
- [x] External email invitees, validated and de-duplicated
- [x] Recurrence editor — frequency, weekdays for weekly, end date with a sensible default
- [x] Validation, all of v2's rules *(`EventFormLogic`)*: title ≥ 3, no past start when
      creating, 30-minute minimum with overnight allowed, recurrence end required and not
      before the start, invitees required for a members event, call type required, location
      required **only** for Meet in Person & Call
- [x] Edit mode with the scope prompt for a recurring series
- [x] **Map picker** *(`MapPickerFragment`)* — `maps-compose` added; tap the map to drop a
      pin, the address is reverse-geocoded as a *suggestion* and never overwrites what the
      user typed. Both the description and the coordinates are saved, so the detail sheet's
      maps link goes to the point rather than to a text search.
      The key lives in `local.properties` as `MAPS_API_KEY` and reaches the manifest as a
      placeholder — not committed as a string resource the way v2 does it.

Verified on the emulator: an event was **created end to end** against the dev API — HTTP
200, the form popped, and the day's count went from 1 to 3 with the new event in the
agenda. Validation was confirmed live too: the start drifted past while the form was open
and the past-time guard caught it.

## Phase 6 — Recurrence

- [x] `EditScope` prompt on edit and delete *(v2: `RecurringActionDialog` — single /
      this-and-future / all)*. A one-off gets a plain confirm instead: a yes/no dialog
      cannot tell "this event" from "all of them", so a series never gets one.
- [x] Move a single occurrence — done as a **scoped edit**: change the date with scope
      `single`. v2 wraps the `/move/` endpoint but never calls it from any screen, and the
      scoped edit is the flow it actually ships. The repository method exists either way.
- [x] Cancelled-occurrence rendering — verified live: a deleted event comes back flagged
      and renders struck through as `Cancelled: …` rather than disappearing
- [x] Virtual occurrences — **hardened**. They arrive with no `_id` at all, so anything
      keyed off `event.id` silently breaks. Every write and fetch now goes through
      `effectiveMasterEventId` plus the occurrence date; the detail sheet remembers the id
      it was opened with rather than reading it back off the response; and the occurrence
      expansion is keyed by master + start rather than by a blank id.

Verified on the emulator: a one-off delete asked for confirmation, sent
`DELETE /calendar/{id}`, came back `calendar_event_deleted_successfully`, and the event
re-rendered as `Cancelled: V3 Phase5 AllDay`.

## Phase 7 — Badges

- [x] Calendar unit badge in the Home strip — already a prefix query on the existing badge
      tree (`home_label` → the calendar unit); no calendar-specific code needed
- [x] Pending-invitation badge on the Events quick action *(v2: `CalendarPending`)*
- [x] Expired badge *(v2: `CalendarExpired`)*
- [x] Mark read when an invitation is actioned *(v2: `CalendarBadgeBookkeeper.onInvitationActioned`)*
      — keyed on the **event id**, so answering one invitation does not clear the rest
- [x] Mark read when the Expired tab is opened *(v2: `onExpiredTabOpened`)*, on arrival as
      well as on switching to it
- [x] **No new counters.** Pending and expired are the *same* unread notifications split by
      whether their event has ended — the way v2 derives them from `endDatetime`. Separate
      badge paths would double-count against the project total on the logo, since those
      notifications already feed the Calendar unit's badge.
- [x] Marking read re-derives the whole badge tree, so the unit badge and the project total
      drop with the calendar counter instead of going stale

**Not observed non-zero.** The counts render 0 on the dev project because the test account
created every event and holds no invitation — the same gap that stopped Phase 4's
accept/decline being exercised. The pipeline runs (`recompute → 18 paths, 57 unread`) and
the calendar query returns cleanly; what is unproven is the number appearing on the pill.
Needs a second account to invite this one.

## Phase 8 — Realtime

- [x] Socket listeners for `create:event`, `edit:event`, `delete:event`
- [x] **Also driven by stored notifications** — and this is the channel that actually
      fires. A live create emitted **no `create:event` at all**: only `chat-room:create`
      (the call's CnC group) and `notification:save` carrying the event under
      `calendar_data`. Reacting to the stored notification is channel-agnostic, so the same
      change delivered by FCM in the background lands too.
- [x] **Targeted** per-event fetch on `event_id` rather than a window refetch — the events
      index is eventually consistent and a window fetched ~250 ms later routinely misses the
      new event *(v2 documents this at length in `BaseSocketListener.triggerTargetedCalendarSync`)*
- [x] Broad window refresh in parallel, for the grid and per-day counts
- [x] One change stream every calendar screen listens to *(v2: `RefreshBus`)*
- [x] Suppress the echo of this device's own action, 2 s as in v2, applied to create, edit,
      delete and invitation answers *(v2: `RefreshBus.markSelfAction`)*
- [x] Expiry handled by the badge derivation rather than a `calendar:expired` message —
      pending and expired are one set split by the clock, so an event becoming expired needs
      no signal at all

**Not observed end to end**, for the same reason as Phases 4 and 7: the notification for
this user's own create is correctly **skipped as `self=true`**, so no badge or refresh can
be triggered by acting alone. The log confirms the classification is right —
`section=home_label tool=calendar_label unit=…` — which is what the badge query pins to.

## Phase 9 — Native device-calendar sync

*v2: `sync/` — 24 files. Writes events into the phone's own calendar.*

- [x] Permission handling (`READ_CALENDAR` / `WRITE_CALENDAR`) — asked at the moment the
      switch is flipped, never at launch
- [x] Calendar provider + target selection *(`CalendarProvider`, `CalendarTarget`)*,
      defaulting to a Google account so the mirror reaches the user's other devices
- [x] Event writer + mapper *(`NativeEventMapper`)*
- [x] RRULE mapping *(`RRuleMapper`)*, including the `0 = Sunday` → `SU` convention and
      `CUSTOM` meaning "weekly on these days"
- [x] Sync queue, worker, backoff, executor — WorkManager-backed, de-duplicated by
      operation, exponential backoff to an hour, dead-lettered after 6 attempts
- [x] Local mapping store so an event is updated, not duplicated *(`NativeMappingStore`,
      `NativeCalendarMappingEntity`, schema 14)*
- [x] Content hashing to skip unchanged events *(`EventHash`)*, with a version prefix so a
      mapping change re-writes every existing row exactly once
- [x] Orphan sweep — run as part of the backfill, the one moment the app knows the full
      live set
- [x] Sync health banner: up to date / waiting / failed / needs permission / no calendar,
      each with the action that resolves it
- [x] Settings screen with the toggle, target picker, sync now, and reset-and-resync
      *(this covers Phase 10 as well)*

Verified on the emulator: permission prompt on toggle, and — the emulator having **no
calendars at all** — the "No writable calendar on this device" state renders correctly
instead of the switch silently sliding back.

**Not verified:** an actual write into a device calendar. The emulator has no account, so
there is nothing to write to. Needs an emulator or device with a Google account signed in.

## Phase 10 — Settings

Read against v2's `SettingsActivity` control by control, not assumed.

- [x] Calendar settings screen *(v2: `ui/settings/SettingsActivity`)*, reached from a gear
      in the calendar header. v2 hides its own gear because the host app owns those
      settings elsewhere; v3 has nowhere else for them yet.
- [x] Sync on/off + which device calendar to write to *(`CalendarSyncPreferences`)*
- [x] Health line with the action that resolves it *(v2: `SyncHealthBanner`)*
- [x] Sync now / Retry
- [x] Change target calendar
- [x] Reset and re-sync all
- [x] **Open Settings** — Android stops showing the permission dialog after a second
      refusal, and the in-app button then silently does nothing. Once that happens the
      banner says so and offers the OS page instead, which is the only route left.
- [x] **Diagnose** *(v2: `SyncDiagnostics`)* — permissions, toggle, target **and whether it
      still exists**, every writable calendar, rows this app owns, the mapping store,
      orphaned mappings, and the queue with per-job errors. Copyable, because the point is
      to paste it to someone who can act on it.
- [x] Stale-target guard in the executor — a stored calendar can outlive the account that
      owned it, and writing to a dead id fails one row at a time with nothing to show for it

Verified on the emulator: the report renders and correctly diagnoses this device —
permissions granted, no writable calendar, nothing queued.

**Not carried over:** v2's "run inline sync" button, which bypasses WorkManager to prove
whether WorkManager is the problem. It exists there because v2's queue was misbehaving;
adding a second execution path before there is a symptom would be inventing a fix for a
problem this build has not had.

## Cross-cutting

- [x] No hardcoded strings — verified by grep: no literal display text anywhere in
      `feature/calendar` or `core/calendar`
- [x] Light + dark tokens — verified: no raw `Color(0x…)` anywhere in the module; the one
      literal is `Color.White` for a tick on a saturated swatch, where the token would be
      wrong in both schemes
- [x] Rotation — everything typed or chosen survives: form fields live in the ViewModel,
      and the sheets' own state (invitee selection, external emails, decline reason, the
      dropped map pin) is `rememberSaveable`. Only sheet-open flags and a transient
      validation message are plain `remember`.
- [x] Reuse `ZillitConfirmDialog` for the one-off delete confirm; the recurrence scope uses
      one shared sheet across edit, delete and accept/decline
- [x] Reuse `DateTime` helpers — zone-aware formatting was added there rather than growing
      a calendar `DateUtils`
- [x] Per-project scoping — the calendar clears and reloads on a project switch, the badge
      query is project-scoped, and every request is signed `WITH_PROJECT_USER_ID`

## Still open

- **"Request reason"** on a decliner row — needs the Chat & Calling module.
- **Verification blocked on a second account**: accept/decline, the badge counts appearing,
  and the realtime refresh. All three need someone else to invite this user.
- **Device-calendar write** — unverified on the emulator, which has no account and so no
  writable calendar.

---

## Notes carried from the v2 read

- **Two calendars exist.** `bottomNav/calendar` is dead weight; only `new_calendar` counts.
- **`start_datetime` is already per-occurrence.** Corrected against a captured payload: the
  backend computes it per row when it expands a series, and v2 uses it as-is. An earlier
  note here said to prefer `_virtualOccurrenceDate` — doing that would move every recurring
  event to midnight, because the occurrence date carries no time.
- **Cancellation has four spellings and deletion two.** `_isCancelled` (production),
  `is_cancelled`, `_isDeleted`, `is_deleted`; plus `deleted_at` **and** a legacy `deleted`
  epoch that the `/events` endpoint still uses for per-occurrence cancellations. Missing
  the last one is what made single-occurrence deletes invisible in v2.
- **Cancelled events are not filtered out.** They render struck through and faded. A
  called-off shoot day that simply vanished is worse than one visibly cancelled.
- **Invitees arrive under two names.** Event *detail* sends `invitations`; the events
  *list* sends `invitees` with an `invitee_count`. Both are read.
- **`#FFFFFF` means "no colour chosen"**, not white — it maps to the default orange.
- **Only the Received row gets a count.** The badge model publishes pending and expired
  only, so a list-size chip on Created or Declined would double-count against the
  project total on the bottom-nav pill. Accepted gets none either — it is a confirmation
  list, not an unread one.
- **The list endpoints are cursor-paged, the window fetch is not.** `/invite`,
  `/created-events` and `/declined-created` answer `{data: {items, cursor, hasMore}}`;
  `/events` and `/join-call` answer a bare array. The cursor itself arrives as a **string
  or a number** depending on which field the list sorts by.
- **Calendar changes arrive as notifications, not as `create:event`.** A live create
  produced `chat-room:create` and `notification:save` and nothing else. The socket
  listeners stay — they may fire for other users' actions, which one account cannot prove —
  but the notification path is what the screens actually depend on.
- **The event's end time is `reference_data.expired`**, repeated inside
  `reference_data.calendar_data.end_datetime`. It is *not* a bare `end_datetime` at that
  level; reading that found nothing, so every calendar notification looked like it had no
  event attached and never reached the counters.
- **A calendar notification is `section=home_label, tool=calendar_label`** with the unit id
  attached — confirmed from a live payload, and what the badge query pins to.
- **Creating an event also creates a CnC group** (`chat-room:create`, `cnc_group_id` on the
  response), which is how a call gets somewhere to happen.
- **All-day events need UTC midnight of the *local* date.** The server stores local
  midnight as an epoch; writing that raw makes the platform read the day before for anyone
  east of UTC, so an event on the 12th shows on the 11th. v2 shipped that bug and fixed it
  with a hash-version bump.
- **Both timezone columns must be set.** Some stock calendars render the time once per
  column and label each; an empty `EVENT_END_TIMEZONE` shows a literal "null" beside the
  second copy.
- **One native row per series, not per occurrence.** The platform expands the RRULE itself;
  writing each expanded occurrence would duplicate every date.
- **Identity lives on the row.** `CUSTOM_APP_PACKAGE` + `CUSTOM_APP_URI` carrying
  `u=<userId>` is what lets a purge or an orphan sweep find rows after a reinstall has
  wiped the mapping store.
- **Pending and expired are one set, not two counters.** v2 counts badge entries carrying
  an `endDatetime` and splits them on whether the event has passed. v3 stores that end time
  on the notification (`eventEndAt`, schema 13) and derives both the same way — so expiry
  follows the clock instead of a stored number that goes stale as the event passes.
- **A virtual occurrence has no `_id`.** It carries `master_event_id` and its own dates and
  nothing else, so `event.id` is the empty string. Anything that addresses, caches or keys
  off it must use `effectiveMasterEventId` (plus the occurrence date) instead.
- **`invitees` has two shapes.** The list endpoints send objects; **create and edit send
  bare user-id strings**. A strict declaration turns a successful create into a reported
  failure — the event saves, the app says it did not. Read through a tolerant serializer
  that accepts either.
- **Invitees are sent in three shapes at once**: `invitees` (current), plus the legacy
  `invited_users` and `email`. The edit endpoint diffs against the legacy pair, so sending
  only the modern shape saves every field *except* the invitee list.
- **Delete uses `delete_type`, edit uses `edit_type`, accept/reject use `scope`.** Three
  names for the same idea on three endpoints; the wrong one deletes a whole series.
- **`edit_type` is mandatory on every edit**, and `single` / `thisAndFuture` must **drop**
  `recurrence_rule` or editing one date rewrites the series' recurrence.
- **`recurrence_end` is end-of-day in the *device* zone**, not the event's — the user picked
  a date off a calendar, not a moment abroad.
- **Accept/reject send the occurrence date as a *number* in the body**, while every query
  parameter elsewhere uses the `yyyy-MM-dd` string. Sending the string form here returns
  200 and answers for the whole series.
- **`scope` is always sent**, even for a one-off event, where the server expects `all`.
- **An occurrence is addressed by its master id plus its date.** Answering by the
  occurrence's own id either fails or silently answers for the series.
- **v2 renders the detail sheet in device time but the card in the event's zone**, so the
  same event shows two different times. v3 uses the event's zone in both and names the zone
  when it differs from the reader's.
- **A `@Serializable` class must never declare a `private companion object`.**
  kotlinx-serialization puts the generated `serializer()` there, and a reified
  `api.get<Foo>()` in another class then throws `IllegalAccessError` — at **runtime**, with
  a clean compile. `EventDto` hit this and crashed the Created list; the constants moved to
  private top-level values.
- **Trimmed responses.** Accept/reject answer with `{event_id}` only. Every DTO field needs
  a default or a successful 200 decodes as a failure — v2 shipped that bug and reported
  successful accepts as errors.
- **Timezone is per event.** A 9am call set in Mumbai stays 9am Mumbai; rendering in the
  device zone silently moves it.
- **Two picker endpoints in v2 are dead.** `calendar/project-users` and `calendar/departments`
  are 404-safe stubs, commented as such in `CalendarApiImpl`; the real picker reads Realm and
  SharedPref. v3 reuses `ProjectDirectory` and adds no calendar-specific user store.
- **Occurrence statuses belong to whoever asked for them.** The payload is caller-scoped, so
  it answers "what did *I* say about this occurrence", never "what did *they* say".
- **`status_overrides` vs `statusOverrides`.** Snake case on an invitation, camel case on the
  occurrence-statuses payload. Both spellings are real; neither can be dropped.
- **Department and designation names are label keys**, resolved through the server label
  dictionary at render — storing resolved text would freeze the language.

## Round 11 — client feedback on the grid badge and the create-event page

All seven points actioned; each verified on the emulator against dev.

- [x] **1. Event count on the grid as an orange badge**, not red. `CountBadge` reused from
      `core/ui/components`; red stays reserved for unread badges.
- [x] **2. Create-event page rebuilt to v2's page.** Event type as two tabs at the top,
      call type as a dropdown, then v2's field order throughout (see point 5).
- [x] **2a. Invite Members is its own page** with v2's three tabs — All Departments
      (admin-only), Select Department, Select Users — sharing one selection across them.
- [x] **2b. External guests are search-first.** The external-user API is called and stored,
      and a search that matches nobody offers Add External User with the typed address
      already filled in.
- [x] **2c. Add/Update External User** rebuilt with v2's full field set and rules:
      Full Name (no special characters), Email (format-checked, fixed on edit), Gender,
      User Type (Crew Member / Vendor / Others, Others requires its own wording),
      Department + Designation (crew members only, both required), Country + Phone as a
      pair. Lives in `core/ui/externaluser` so the settings module can reuse it.
- [x] **3. One shared map page.** `core/ui/location/LocationPickerScreen` — Places search,
      centre pin with camera-idle reverse geocoding, my-location, Clear and Confirm. The
      form's LOCATION field is read-only and opens it.
- [x] **4. "The organizer will not be a part of this event" moved to the bottom** of PEOPLE.
- [x] **5. Field order matches v2**: EVENT (Title, Color, Full Day, Start date, End date,
      Start/End time, Timezone, Repeat) → Details (Description, Location, Reminder) →
      PEOPLE (Call type, Invite members, External guests, organiser toggle).
- [x] **6. Colour picker has Presets and Custom** — twelve named presets, or a hex field
      with R/G/B sliders.
- [x] **7. Copy is v2 verbatim.** Two additions flagged for approval: `calendar_color_presets`
      / `calendar_color_custom` (v2 has no custom colour) and `map_picker_clear` (v2's Clear
      button points at `@string/clear`, which reads "Clear Signature").

### Reuse this round
- `StackedField`, `FormSectionHeader`, `SelectionSummary` and a new `DropdownField` moved to
  `core/ui/components/FormFields.kt`; the calendar's three hand-rolled dropdown menus now go
  through the shared one.
- The country list moved out of create-project into `core/preset/CountryCodeRepository`,
  fetched once and shared.
- `ProjectDirectory.designations(projectId, departmentId)` reads designations back out of the
  department payload already on disk — no second endpoint.

### Notes added to the wire-format list
- **External users live in the project user table**, flagged `isExternalUser`, exactly as v2
  does. `ProjectDirectory.observeUsers` and its delete-then-store both have to filter on that
  flag, or opening a project wipes every external guest.
- **`external_user_type` is one field carrying two things**: a label key for the known kinds,
  or free text for "Others". There is no separate "other type" field on the wire.
- **The filter is spelled `ExternalUserType` as a query parameter** and `external_user_type`
  in the body. Both spellings are real.
- **Create/update return `data` as a bare object; the list returns an array.** A strict
  declaration reports a successful save as a failure — the guest is created and the app says
  it was not. Read through a tolerant serializer.
- **The Places key is not compiled in.** It arrives AES-encrypted from `GET configuration`
  as `places_secret`, so search only works once configuration has been fetched.

## Round 12 — the create-event page rebuilt against v2's own layout

Round 11 matched v2's field *order* but not its *layout*. Rebuilt from
`new_calendar_fragment_event_form.xml` rather than from a reading of the screen:

- [x] **Members / Personal are two full-width buttons** — the selected one filled orange with
      white bold text, the other white and outlined. Not a segmented pill in a grey track.
      Shown in create mode only; an event cannot change kind once it exists.
- [x] **Three bordered section cards**, each introduced by a 28dp tinted icon chip:
      EVENT (calendar), DETAILS (pencil), PEOPLE (people).
- [x] **Colour and Full Day share one row**; the whole Full Day label toggles, not just the
      switch.
- [x] **Start date and end date side by side**, end read-only and dimmed. **Start and end
      times side by side.** Both the end date and the whole time row disappear for a full-day
      event, exactly as v2 hides them.
- [x] **Timezone carries a chevron pointing on**, not down — it opens a screen, not a menu.
- [x] **Invite Members and External Guests are outlined buttons** followed by a count bar
      ("N selected" in brand, "Clear" in warm terracotta), not tappable value fields.
- [x] **Exclude-me is a checkbox**, last in PEOPLE.
- [x] **Create Event is pinned to the bottom** behind a divider, outside the scroll, with any
      validation message directly above it.

### Reuse from this round
`core/ui/components/FormFields.kt` now holds the whole form vocabulary — `SectionCard`,
`FieldLabel`, `FieldBox`, `LabeledField`, `FormTextField`, `DropdownField`,
`SegmentedButtons`, `CountBar` — and the external-user sheet is built from the same set, so
typed, picked and chosen values all wear one box. The calendar's local `FormFields.kt` is
gone. One deliberate deviation: v3's own palette (cool background, `border`/`textTertiary`
tokens) rather than v2's warm `#FBF8F4` / `#8B7355`, because those tokens are app-wide and
carry the dark theme; only `accentWarm` was added, for v2's terracotta Clear.

### Fixed while verifying
- **A created event did not appear until something else refetched.** The window is fetched,
  not observed, and the socket echo of your own save is deliberately ignored — so returning
  from the form left the grid showing the state from before it. `CalendarRoute` now refreshes
  on resume (skipping the first, which the ViewModel has just loaded).

### Still open
- **Request reason** on a decliner row — needs the Chat & Calling module (v2 sends a 1:1
  chat, not a calendar call).
- **Updating** an external guest: the repository and the sheet both support it, but nothing
  in the calendar opens it — that belongs to the external-user module.
- **"Add more information"** (v2's dynamic `other_info` key/value rows on the external user
  page) is not carried over.
- **Native device-calendar sync** is written but unverified: this emulator has no calendar
  account.

## Round 13 — badge sync, push, and how the calendar reads its badges

Three reports: notifications not arriving, badges not syncing on app open, and the calendar
not clearing its badge the way a unit chat does. Checked against v2 and the running app.

### 1. Push — the app's side is correct
- FCM registers on every launch. Verified on the emulator: `Registering device, token=…` →
  `Device registered`, so the server has an address to push to.
- The channel `zillit_general` exists and notifications are permitted for the app.
- The receive path (`ZillitMessagingService`) stores unconditionally, badges unconditionally,
  and only posts to the tray for a non-silent message with notifications unmuted.
- **Not verifiable on this emulator**: nothing can be made to arrive. Every notification this
  account would receive is raised *by* this account, and the server marks those `self=true`
  and does not push to the originator. A synthetic push cannot be injected either — the
  `c2dm.RECEIVE` broadcast requires a permission `adb shell` does not hold (`result=0`).
  End-to-end delivery needs a second account on another device.

### 2. Badges now sync when the app is opened — three real bugs behind this
- **The server's read flag was never read.** `message_read` was not on the payload model at
  all, and an existing row was skipped entirely on sync, so a notification read on web or on
  another phone stayed unread here forever. Now parsed, and merged in v2's direction: a
  remote read clears the badge here, a local read is never undone by a server that has not
  caught up.
- **The cursor was on `created`, not `updated`.** v2 syncs from the newest `updated` among
  rows the feed itself produced; reading a notification bumps `updated`, which is how a
  device that was asleep learns the count dropped. Syncing from `created` could never see a
  read. Entity gained `updatedAt` and `fromSync` (schema **15**) so the cursor is taken from
  the feed's own rows — a push arrives stamped "now" and would otherwise skip everything
  older that had not been fetched yet.
- **Nothing resynced on foreground.** v2 refetches every time its main screen is created.
  v3 only reconciled on project switch and network restore, so returning from the background
  — exactly when the socket has been dead and pushes dropped — reconciled nothing.
  `BadgeSyncCoordinator` now watches `ProcessLifecycleOwner` for `ON_START`.

Verified live: backgrounding and reopening emits `notification:missing`, fetches
`…/notifications/{updated}/next`, and the project total dropped **57 → 50** — seven
notifications the server already held as read that the app had been showing.

### 3. Calendar badges: shown like v2, and now read like v2
How v2 does it, confirmed in `CommonBadgesHandler.computeBadgesCount`:
- Home unit badges and the Home tab total both **exclude `tool == "calendar_label"`**
  (`home_label.filter { it.tool != "calendar_label" }`). Calendar invitations are not a
  unit's unread.
- They are counted separately as `calendarPending` / `calendarExpired`, shown as one pill on
  the **Events button** and as a chip on the **Received** row. Created and Declined carry no
  chip.
- They are cleared by exactly two gestures: **accepting or rejecting** an invitation
  (subtract that event) and **opening the Expired tab** (read the expired ones). Entering
  the calendar clears nothing — an invitation stays unread until it is answered.

What was wrong in v3:
- Calendar rows were counted in the Home strip and the Home tab, so opening the Calendar
  unit marked every invitation read — the opposite of v2 — and the Events pill went with it.
  `BadgeManager` queries and `NotificationRepository.markRead` now take `excludeTool`, and
  `BadgeTool.CALENDAR` is excluded from the strip, the auto-read-while-open watcher, the
  unit read, and the Home tab total.
- The calendar's own reads wrote `isRead` straight into Realm and told the server nothing,
  so the count came back on the next sync and other devices never learned. Both gestures now
  go through `BadgeSyncCoordinator`, like a chat unit does.
- A read could not name what it was about. `markReadForReference(referenceId, segment)` sends
  v2's shape — `segment=calendar_invite_users` plus the event's `reference_id` — and the path
  echo now carries `section`/`tool`/`unit`/`level_n` instead of a bare segment.
- The Expired tab echoes **one read per expired event** rather than v2's bare
  `calendar_invite_users`, which tells the server to clear pending invitations too.

Not verifiable here for the same reason as push: this account has no unread calendar
invitations, because it created every event. The mechanism is in place and the counts it
feeds are unchanged.

## Round 14 — where the counts show, matched to v2's screens

Three screenshots of v2 settled how the calendar's numbers are meant to look.

- **Day cells** carry the event count as an orange pill on the **cell's top-right corner**,
  not stacked under the date. Anchoring it to the number instead covers the digits, and
  stacking it makes every row taller.
- **The Calendar chip in the Home strip carries the invitation count.** v2 special-cases
  exactly this unit — `if (unit.identifier == CALENDER_IDENTIFIER) badges = calendarPending +
  calendarExpired` — while every other chip counts what is unread inside it. So the earlier
  exclusion was only half the rule: calendar rows stay out of the *generic* unit count (which
  is what stops opening the calendar from answering its invitations), and the calendar's own
  counter is put back on that one chip.
- **The Home tab total counts the section whole**, calendar included — v2's bottom-nav badge
  is `homeTotal + calendarPending + calendarExpired`, which is the same set of rows.
- **Events button** → one red pill for pending + expired; **Received row** in its menu carries
  the same number; Created and Declined stay clean. Already matched.
- **Received Events tabs** → red counts on **Pending** and **Expired**, none on Accepted.
  Already matched, and only the Expired tab marks anything read: opening Received, or its
  Pending tab, reads nothing. That is v2's rule and it is what the code does.

## Round 15 — the detail sheet, field by field against v2's layout

Compared against `new_calendar_bottom_sheet_event_detail.xml` and its fragment. What was
missing, now added:

- **Close (✕)**, and **Delete** / **Edit Event** as *named* buttons rather than bare icons —
  deleting an event a whole unit can see should not hide behind a glyph.
- **Created by**, resolved to "Name (Designation)" through the label dictionary, with v2's
  **`- Excluded`** tag beside it when the organiser left themselves out.
- **Created on**, date then time, in the device's own zone.
- **"Tap to view and search"** under the invitee count.
- The event type reads as a soft brand pill, and "Event Details" as v2's orange caption with
  its calendar icon.

Already present and confirmed: title (struck through when cancelled or declined), Recurring
badge, time range + duration, date + week of year, location (tappable, opens maps), call type
+ Join Call gated on joinable/not-expired/not-cancelled, reminder, description, event colour
with swatch and hex, invitee count → invitee list, and the Accept / Decline bar with its
resolved invitation status.

### Fixed underneath
`PrimaryButton`/`SecondaryButton` hard-coded `fillMaxWidth()` **after** the caller's modifier,
so a caller could not opt out: in a row the first button took the whole width and the second
was squeezed to zero — rendering as an invisible, very tall column of wrapped text, which is
what produced the phantom gaps in the header. They now take a `fillWidth` flag, and the
caller's modifier is applied last.

## v2 (Zillit-Android) — restored a file the branch had lost

`EmailReadState.kt` was referenced from three places in the new-email module but did not
exist on `dev_doc_reverted_changes`, so the module could not compile. The file is in git
(added by `d66e84185`) and was restored from there rather than rewritten — it is the
badge-driven read rule the old email module used, and guessing at it would have changed
behaviour. `:app:compileDevelopDebugKotlin` now completes clean.

## Round 16 — why the calendar badge still read on tap, and a notification-page loop

### The calendar badge cleared on entering the unit
Excluding calendar rows from the read was not enough, because the read is **echoed to the
server as a read of the whole unit** — so the server cleared the invitations and told us so.
v2 does not filter; it **skips the read entirely** for that unit, guarding both of its read
paths with `if (identifier != CALENDER_IDENTIFIER)`. `HomeViewModel` now does the same in
`onUnitSelected` and in the auto-read-while-open watcher. The strip's grouped badge query
keeps its exclusion, so the Calendar chip still shows pending + expired and every other chip
still counts its own unread.

### Read-sync was clearing the whole project — including our own echo
`observeReadElsewhere` marked the entire project read whenever any `notification:read:sync`
arrived. Two problems: it cleared far more than was read, and **this device's own read echo
comes back on that channel**, so opening any unit wiped every badge in the project. v2 treats
the event as a prompt to refetch (`updateBadges()`), which is now what happens — and the
incremental sync is exactly the right tool, since a read bumps `updated` and the merge added
in round 13 applies the read state.

### The notification page called its API in a loop
`fetch()` ended by marking the whole project read. That emitted a read, the server synced it
back on `notification:save` / `notification:silent`, the feed's `incoming()` listener refreshed
on it, and the refresh marked read again — a permanent loader over a list reloading forever,
and a read echo per cycle. Fixed at both ends:
- The read now happens **once, on open**, not after every page (paging older notifications was
  re-reading the whole project each time).
- `incoming()` ignores this device's own echo (`self`, `self_device_id`).

Measured after the fix: 50 seconds on the notification screen → **1** API call, **1** read
echo, **1** badge recompute.

### Fixed while verifying
The first version of the calendar skip read `units.value` from a collector started in `init`,
before that property is constructed — a NullPointerException that took the app down on launch.
The selected unit's kind is now tracked in its own `MutableStateFlow` alongside its id.
