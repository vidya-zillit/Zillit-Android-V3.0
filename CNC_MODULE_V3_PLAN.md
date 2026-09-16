# C&C module — phased plan and status

Same shape as the Tools plan: phases in build order, each one testable on its own, each
marked with where it actually stands. The v2 contract behind it is in
`CNC_DATA_LAYER_V2_AUDIT.md`; this document is only "what phase are we in".

**Status at a glance**

| Phase | What it delivers | State |
|---|---|---|
| 1 | UI — the screens and navigation | ✅ Done |
| 2 | Storage — Realm tables | ✅ Done |
| 3 | Transport — socket and REST | ✅ Done |
| 4 | Repository — send, receive, history | ✅ Done |
| 5 | Presence and typing | ✅ Done |
| 6 | Wiring — screens read real data | ✅ Done |
| 7 | Attachments in a conversation | ✅ Done (group picture pending) |
| 8 | Badges | ✅ Done |
| 9 | Calls tab | ✅ Done (read and clear) |
| 10 | Block state, live room events, read-on-scroll, read counts | ✅ Done |
| 11 | Budget as the second surface | ✅ Ready (needs the Budget tool to prove) |
| — | Calling | ⏸ Deferred by you |

Phases 1–6 are what makes the tab work at all. **7 onwards are additions to a working
module**, not prerequisites for it.

---

## ✅ Phase 1 — UI

Three list tabs, direct and group threads, person profile, group info, create group. Each on
its own navigation destination, so a conversation is a page rather than a panel and the back
arrow sits in the same bar as the person's name.

Built: `CncScreen`, `ChatTabScreen`, `CallTabScreen`, `ContactsTabScreen`,
`CncThreadScreen`, `CncThreadHeader`, `ProfileScreens`, `CreateGroupScreen`.

Threads reuse the app's one chat surface in a new two-sided mode, so replies, the day chip,
multi-select, search, voice notes and the attachment sheet all arrive without a second
implementation.

## ✅ Phase 2 — Storage

`CncMessageEntity` and `CncConversationEntity` with their embedded children. Realm schema
24 → 26.

Kept separate from the unit-chat tables on your instruction, and for a concrete reason: the
two use the same status numbers for different things. A unit message's `1` means uploading;
a socket message's `1` means sent.

## ✅ Phase 3 — Transport

`CncSocketDataSource` — every event in the audit, typed, for both surfaces. Sends wait for
the acknowledgement, because on a socket chat that is the only proof a message landed. v2's
30-second duplicate-send guard is carried over.

REST for history and rooms. Endpoints on `ChatSurface`.

## ✅ Phase 4 — Repository

`CncRepository`: send, reply, edit, delete, react, mark read, paged history, offline
resend. `CncRealtime` holds every subscription and starts with the project, so messages land
whether or not the tab is open. On reconnect it asks for what was missed, resends what is
queued and re-announces presence.

`CncDirectory` supplies the names, photos and device ids the recent-list endpoint does not
return.

## ✅ Phase 5 — Presence and typing

`PresenceRepository` — emits `mark_online` / `mark_offline` and reads presence from Firebase
Realtime Database, which is the half v2 also depends on. Driven by the process lifecycle, so
rotating no longer blinks you offline.

`TypingController` — v2's events and payload, but it sends `end` when typing stops instead
of leaving every other client to wait out a four-second timeout.

## ✅ Phase 6 — Wiring

Four view models replace the sample data, which is deleted. Lists, thread, profile, group
info and create-group all read Realm. Favourites, block/unblock and the group lifecycle
(create, rename, membership, admins, leave) are wired.

---

## ✅ Phase 7 — Attachments in a conversation

Files now send from a C&C thread, through the **same queue the unit chat uses** rather than
a second one. A chat file therefore inherits everything that queue already does: it survives
the app being killed, waits for a connection, uploads three at a time and retries on its own.

How the two kinds share it: the queue row gained a `deliveryKind`. A unit chat POSTs when the
bytes are up; a socket chat emits and waits for the acknowledgement. Everything before that
last step is identical, which is the whole reason for sharing. Realm schema 26 → 27.

- `UploadQueue.enqueue` takes the delivery kind, `isGroup` and the recipient's device
- `UploadWorker.postMessage` branches to `CncRepository.deliverAttachment`
- `CncRepository.addPendingAttachment` writes the optimistic row first, so a photo appears
  with a progress bar rather than after the upload finishes
- The queue row and the message row share an id, which is how the worker finds the bubble to
  replace

**Still open in this phase:** the group picture upload, which needs the same path pointed at
`chat-room/update-group-picture` instead of a message.

## ✅ Phase 8 — Badges

A conversation's unread count now comes from the **badge tree**, not the recent-list fetch.
That matters because the fetch is a snapshot from whenever it last ran, while the tree moves
the moment a message arrives — including for pushes, which never touch the chat socket.

One real bug found and fixed on the way. A C&C notification identifies its conversation by
room id for a group and by **sender** for a one-to-one, because a direct message has no room.
The badge key only read the room, so every direct-message badge collapsed onto the section
and the C&C tab showed a count no row in it could account for.

- `NotificationRepository.badgeKey()` falls back to the sender for `cnc_label`
- The list groups badges by unit, so each row gets its own count
- An open thread clears its badge whenever one appears, not only on entry — the same
  approach the Home unit uses, and it covers pushes as well as socket messages
- Clearing tells the server too, or the count returns on the next sync

## ✅ Phase 9 — Calls tab

The log now loads. `CallLogRepository` reads `call/{timestamp}/previous`, with `?missed=yes`
for the Missed filter, and clears through the separate `missed` / `recent` paths so emptying
one does not wipe the other.

Deliberately **not** cached in Realm, unlike messages: a call log is read rarely, is useless
offline, and is paged by timestamp — a local copy would need its own windowing to say
anything a fetch does not.

Missed is a **server-side query**, not a filter over the recents already loaded. A missed
call can be older than the newest page of recents, so filtering locally would hide it.

One trap carried over from v2: `call_users` arrives in two shapes, sometimes mixed in one
response — bare id strings on older rows, objects on newer ones. A strict model made a single
old row fail the whole page and the list went blank. The flexible serializer is there for
that reason, not for tidiness.

`CALLING_BASE_URL` was already configured but not exposed as a build field; it is now.

**Still open in this phase:** the per-row actions (call back, call details) stay inert while
calling is deferred.

## ✅ Phase 10 — Block state, live room events, read-on-scroll, read counts

**A real bug fixed.** The block request I wrote in Phase 6 used invented field names and the
wrong verb. v2 sends a **PUT** with `to_user_id` and `user_blocked`; mine sent a POST with
`blocked_user_id` and `is_blocked`, which the server would have ignored. `from_user_id` is
deliberately not sent — the actor comes from the signed header, and guessing it records the
block against the wrong person.

**Block state is now loaded, not just written.** Blocks live on the server and nothing was
fetching them, so the banner was only ever right for a block made on this device in this
session. Loaded at bootstrap, on reconnect, and whenever a block event arrives. Each row is
directional: the one I made names me as `from`, the one made against me names me as `to`,
and those mean different things to the thread.

**Room events applied live.** `chat-room:create` / `remove` / `updated` all refetch the room
list. They carry only the room that changed, but membership and admin rights are the
server's decision and a local patch that disagrees with it is worse than one extra call —
it is also what makes a group you were just added to appear without a manual refresh.

**Read on reaching the bottom, not only on open.** A long thread opens above its own bottom,
so marking read on entry claims the user has seen messages they have not. `ChatThread` gained
an `onReachedBottom` signal for this, which the unit chat can use too.

**Group read counts** come from `group-chat/readby`, fetched on open and on reaching the
bottom. Counting the read-until events locally cannot work: they name a reader and a
watermark, not a total, and anyone who read before this device was listening is invisible.

### Not a C&C gap after all
Granting **access rights** — who may reach whom — turned out to be an **admin settings**
feature in v2, driven from the admin screens with `{from_user_id, to_user_ids[]}`. It is not
part of the C&C tab, and it belongs with the Settings work rather than here.

## ✅ Phase 11 — Budget as the second surface

**A real bug in my own mapping, found by checking rather than assuming.** `ChatSurface` said
the 1-1 read-until event was "the single irregularity" and kept its name on both surfaces.
That was invented. v2's budget handler sends `budget:private_chat_message_read_untill`, so
every Budget read receipt would have gone out on the C&C channel where nothing listens, and
no tick would ever have turned blue on a Budget thread. The mapping has **no** irregularities:
prefix everything.

Verified against v2's `BudgetSocketEventHandler`, event by event. REST paths check out too —
`budget-private-chat/messages`, `budget-group-chat/messages`, `budget-group-chat/readby`,
`budget-group-chat/archive-status`.

**Budget has no expiry events.** Retention is a C&C setting. The names are still generated so
the subscription is uniform; they simply never fire.

**The thread is no longer pinned to C&C.** It reads its surface from the route, and carries
`departmentId` and `budgetDocumentId` through sends, typing and history. That matters because
Budget is *two* tools: a department thread and the project-level one send different
`chat_tool` values and the receiving side filters on it.

### What is genuinely left
Proving it end to end needs a **Budget screen to open a thread from**, and the Budget tool
does not exist in v3 — like all 51 tools, its entry has no destination. So this phase is
"correct and ready" rather than "demonstrated". It costs nothing further until the Budget
tool is built.

---

## Not verified on a device

Phases 1–6 compile and the app launches, but none of it has been seen working against a live
server. The emulator here has no network and its session expired. **That is the first thing
to do before Phase 7** — a phase built on an unverified foundation just moves the debugging
later.
