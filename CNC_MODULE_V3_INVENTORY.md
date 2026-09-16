# C&C (Chat & Calling) — v2 inventory and v3 plan

Survey of what v2 has, what v3 already has, and what is left. Nothing here is implemented
yet; this is the list to work through.

## Size of the thing

| v2 area | files | lines |
|---|---|---|
| `chatAndGroupChat/` — the thread, socket, adapters, offline queue | 30 | ~9,100 |
| `bottomNav/chatAndCall/` — landing, tabs, groups, call sheets | 16 | ~4,000 |
| `bottomNav/tools/budget/budgetChat/` — the **second host** | 3 | ~5,000 |
| `callingv2/` + `livekit/` — calling | 40+ | large |

The single biggest file is `ChatAndGroupPage.kt` at 2,999 lines, followed by
`BudgetChatActivity.kt` at 2,736 — which is the duplication the two-host requirement is
really about.

---

## The key finding: C&C chat is socket-first

Home chat, which v3 has already built, is **HTTP-first**: `POST {base}/chat` to send, and
paged `GET {base}/chat/{scope}/{ts}/previous` to read. C&C is not that.

C&C **sends over the socket** with an acknowledgement, and uses REST only for history:

```
socket.emit("private_chat", payload, Ack { ... })     ← 1-1 send
socket.emit("group_chat",   payload, Ack { ... })     ← group send
GET  /api/v2/private-chat/messages                    ← history
GET  /api/v2/group-chat/messages
```

So `ChatModule` as it stands does not cover C&C. It needs a second transport, not a second
base URL.

## The two hosts are the same protocol with a prefix

Budget chat is C&C's event set with `budget:` in front of every name, and its own REST base:

| C&C | Budget |
|---|---|
| `private_chat` | `budget:private_chat` |
| `group_chat` | `budget:group_chat` |
| `private-chat:edit` | `budget:private-chat:edit` |
| `group-chat:read-untill` | `budget:group-chat:read-untill` |
| `chat-room:create` | `budget:chat-room:create` |
| `update_reaction` | `budget:update_reaction` |
| `user:list` | `budget:recent:list` |
| `/v2/private-chat/messages` | `/v2/budget-private-chat/messages` |
| `/v2/group-chat/messages` | `/v2/budget-group-chat/messages` |
| `/v2/group-chat/readby` | `/v2/budget-group-chat/readby` |

**That is the whole difference.** So the v3 shape is one `ChatSurface` carrying an event
prefix and a REST base, and one thread that works for both — which is what the note about
making it compatible for C&C and Budget asks for. v2 instead has 2,999 and 2,736 line
screens that drifted apart.

---

## Every socket event, by concern

**Connection and presence**
`user:join`, `connect`, `disconnect`, `ping`, `connect_timeout`, `connect_failed`,
`connect_error`, `libs_invalid_token`, `mark_online`, `mark_offline`

**Lists**
`user:list` (people you have chatted with), `budget:recent:list`, `pending-messages:get`

**1-1**
`private_chat`, `private-chat:delete-messages`, `private_chat_message_read_untill`,
`private-chat:edit`, `private-chat:typing`, `private-chat:message-expired`

**Group**
`group_chat`, `group-chat:delete-messages`, `group-chat:read-untill`, `group-chat:edit`,
`group-chat:typing`, `group-chat:message-expired`

**Rooms**
`chat-room:create`, `chat-room:remove`, `chat-room:updated`

**Reactions**
`update_reaction`

**Blocking**
`chat-communication:blocked`, `chat-communication:un-blocked`

**Calling**
`call:get-active-group-calls`, `call:delete:log`

## Every REST endpoint

| Purpose | Endpoint |
|---|---|
| Create / update group | `POST/PUT /v2/chat-room` |
| List rooms | `GET /v2/chat-room` |
| Leave group | `/v2/chat-room/leave-group` |
| Promote / demote admin | `/v2/chat-room/edit-admin` |
| Group picture | `/v2/chat-room/update-group-picture` |
| Block / unblock | `/v2/communication-settings/add` |
| Who is blocked | `/v2/communication-settings/list` |
| Chat access rights | `/v2/communication-settings` |
| Disappearing presets | `/v2/disappear/preset` |
| Disappearing settings | `/v2/disappear/settings`, `/v2/disappear/all-settings/` |
| 1-1 history | `/v2/private-chat/messages` |
| Group history | `/v2/group-chat/messages` |
| Group file cabinet | `/v2/group-chat/archive`, `/archive-status` |
| Group read-by | `/v2/group-chat/readby` |
| Mark read to a point | `/v2/private-chat/mark-read-untill/` |
| Favourites | `/v2/favourite-user`, `/add`, `/remove` |
| Chat users | `/v2/chat/users` |

---

## UI surfaces

### Landing — four tabs
Chat, Call, and then either Calendar + SOS (personal projects) or Contacts (everything else).

### Chat tab — three sub-tabs
Members, Groups, Departments. Carries a search field, per-tab badges, and a Create Group
button whose visibility depends on the tab, on admin rights for a personal project, and on
the user's membership not being pending.

### Call tab
Recent calls, active-call detail sheet, ongoing-call sheet, guest join sheet.

### Screens
1-1 thread, group thread, create group, group info with add/remove member and admin
promotion, profile, user search, chat library, file cabinet, chat history.

---

## Business logic that is not obvious

- **Read watermark**, not per-message flags. 1-1 marks read *up to* a timestamp.
- **Disappearing messages** — per conversation, with server presets, plus expiry events.
- **Block / unblock** both ways, and a separate access-rights setting.
- **Favourites** — a pinned subset of people.
- **Offline queue** — pending text messages flushed by a WorkManager job, both one-shot and
  periodic.
- **Reactions** with their own socket round trip.
- **Typing indicators**, per surface.
- **Presence** — online/offline marking.
- **Personal vs production project** changes which tabs exist and who may create a group.
- **Pending members** cannot create groups.

## Badges

Section `cnc_label`, with `chat_label`, `chat_group_label`, `chat_member_label` and
`chat_department_label` beneath it. Same tree v3 already reads, so the tab total and the
per-tab counts should come from `observeGrouped` rather than hand-summed fields.

---

## What v3 already has

About 6,700 lines of shared chat, and most of it is reusable as-is:

`ChatThread`, `ChatBubbles`, `ChatRepository` (Realm-first, optimistic rows keyed on
`unique_id`), `ChatSocketBridge`, `ChatLibraryScreen`, `ReadByUserScreen`, `ForwardShareSheet`,
`MediaViewerScreen`, `ChatMessageOptions`, `ChatSelection`, `ChatSearchBar`, `DraftStore`,
`ChatHistoryRepository`, `ReadByRepository`.

The repository is already written as "one repository for every chat surface".

## What is missing

1. **Socket transport for chat.** The repository sends over HTTP. C&C and Budget need an
   emit-with-ack path, and the surface needs to choose.
2. **`ChatSurface`** carrying the event prefix and REST base, so C&C and Budget are two
   values rather than two screens.
3. **Recent-chat list** — people, groups, departments, favourites, with last message,
   unread and presence.
4. **Group lifecycle** — create, rename, picture, add/remove member, admin, leave.
5. **1-1 concerns** — block/unblock, access rights, disappearing messages, profile.
6. **Presence and typing.**
7. **Offline send queue.** v3 has an outbox for mail; chat needs the same treatment.
8. **Calling.** Untouched in v3, and large enough to be its own phase.
9. **New UI** for all of it.

---

## Proposed order

1. `ChatSurface` + socket transport in the repository. Everything else depends on it.
2. Recent-chat list and the landing tabs, with badges.
3. 1-1 thread on the existing `ChatThread`.
4. Group thread, then group lifecycle screens.
5. 1-1 concerns: block, access, disappearing, profile.
6. Presence, typing, offline queue.
7. Budget host — should be one `ChatSurface` value plus a screen that opens it.
8. Calling, as its own phase.

Steps 1 to 4 give a working C&C. Step 7 should cost almost nothing if step 1 is right, and
that is the test of whether the two-host requirement was actually met.
