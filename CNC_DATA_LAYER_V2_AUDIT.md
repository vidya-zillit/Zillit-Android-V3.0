# C&C data layer — v2 audit and v3 implementation checklist

Everything below was read out of the v2 source, not assumed. File and line references are
to `~/StudioProjects/Zillit-Android`.

v2 spreads C&C over ~25,500 lines in two packages:

| Package | Lines | What it holds |
|---|---|---|
| `chatAndGroupChat/` | 13,759 | the thread: socket, view model, adapters, Realm, offline queue |
| `bottomNav/chatAndCall/` | 9,071 | the landing: chat / call / contacts / groups lists |
| `databases/Chat*.kt`, `UsersDB.kt` | 2,687 | Realm entities and queries |

---

## 1. Socket contract

One Socket.IO connection, shared with the rest of the app. Every event below is in
`ChatSocketHelper.kt:118–205`.

### 1.1 Events this client emits

| Event | Purpose | Payload |
|---|---|---|
| `user:join` | join after connect | none |
| `user:list` | recent chat list (C&C) | `{ user_id, project_id }` |
| `budget:recent:list` | recent chat list (Budget) | same |
| `private_chat` | send a 1-1 message | full `ChatAndGroupModel` (§2) |
| `group_chat` | send a group message | full `ChatAndGroupModel` |
| `private_chat_message_read_untill` | 1-1 read watermark | `{ user_id, _id, status, project_id, department_id, chat_tool }` |
| `group-chat:read-untill` | group read watermark | `{ room_id, user_id, _id, status, project_id, department_id, chat_tool }` |
| `private-chat:delete-messages` | delete | `{ message_ids: [..], project_id }` |
| `group-chat:delete-messages` | delete | same |
| `private-chat:edit` / `group-chat:edit` | edit body | `{ _id, message, message_translation, message_elements }` |
| `private-chat:typing` / `group-chat:typing` | typing | `{ receiver, sender, status, chat_tool, department_id, budget_document_id, platform }` |
| `update_reaction` | add/change reaction | `{ _id, reaction }` |
| `pending-messages:get` | resync after reconnect | `{ timestamp, project_id, platform }` |
| `mark_online` / `mark_offline` | presence | `{ project_id, user_id }` |
| `call:get-active-group-calls` | ongoing calls | `{ projectId, user_id }` |

Every emit takes an **acknowledgement**, and the ack is what persists the server id. A send
that is not acked stays `status = 0` and is retried.

### 1.2 Events this client listens to

`private_chat`, `group_chat`, `private_chat_message_read_untill`, `group-chat:read-untill`,
`update_reaction`, `private-chat:delete-messages`, `group-chat:delete-messages`,
`private-chat:edit`, `group-chat:edit`, `private-chat:typing`, `group-chat:typing`,
`pending-messages:get`, `chat-room:create`, `chat-room:remove`, `chat-room:updated`,
`call:delete:log`, `private-chat:message-expired`, `group-chat:message-expired`,
`chat-communication:blocked`, `chat-communication:un-blocked`.

### 1.3 Duplicate-send guard

`ChatSocketHelper.kt:538` keeps a 30-second in-flight window keyed on `unique_id`. Several
flush triggers overlap on reconnect (socket connect sweep, the worker, every push-driven
flush) and QA saw one offline message delivered three times. **Must be carried over.**

---

## 2. The message model

`ChatAndGroupRequestModelHandler.kt:29`. The same object is the send payload, the receive
payload and, field for field, the Realm row.

Identity and routing: `project_id`, `unique_id` (client-generated, the primary key), `_id`
(server id), `type`, `message_type`, `sender`, `receiver`, `receiver_device_id`,
`sender_device_id`, `chat_tool`, `department_id`, `budget_document_id`, `platform`.

Content: `message`, `message_translation`, `message_elements[{search, replacer}]`,
`attachment`, `attachments[]`, `location`, `reply`, `reactions[{user_id, reaction, updated}]`,
`message_group` (batch id).

Lifecycle: `status`, `created`, `updated`, `edited`, `deleted`, `archived`, `expired`,
`last_activity`, `isTranslated`.

`chat_tool` values (`Constants.kt:1911`): `cnc_section`, `main_budget_tool`,
`department_budget_tool`.

`message_type` values (`Constants.kt:717`): `text`, `image`, `video`, `audio`, `document`,
`location`, `attachemnt` *(spelled that way on the wire)*, `group_notification`,
`budget_document`, `pdf`, `casting`, `wardrobe`.

`status`: `0` pending · `1` sent · `2` delivered · `3` read.

---

## 3. REST endpoints

`ApiUrl.kt:429–453`, base `{CHAT_BASE_URL}/api/v2`.

History and archive: `private-chat/messages`, `group-chat/messages`, `group-chat/archive`,
`group-chat/archive-status`, `group-chat/readby`, `private-chat/mark-read-untill/`.

Rooms: `chat-room` (create/update/list), `chat-room/leave-group`, `chat-room/edit-admin`,
`chat-room/update-group-picture`, `chat/users`.

People: `favourite-user`, `favourite-user/add`, `favourite-user/remove`,
`communication-settings/add` (block/unblock), `communication-settings` (access rights),
`communication-settings/list`.

Budget equivalents (`ApiUrl.kt:824`): `budget-private-chat/messages`,
`budget-group-chat/messages`, `budget-group-chat/archive-status`, `budget-group-chat/readby`.

Calls: `GET/DELETE {CALL_BASE_URL}call`.

Not carried over, per your instruction: `disappear/preset`, `disappear/settings`,
`disappear/all-settings/`.

---

## 4. Local storage

`ChatGroupModelDB` (`ChatAndGroupDb.kt:1035`) with `unique_id` as primary key, plus
`AttachmentDB`, `Reply_chatDB`, `ReactionModelDB`, `PathElementsDB`, `LocationDB`.
Rooms in `ChatRoomsDB`, people in `UserDataDb`.

Operations worth carrying (`ChatAndGroupDb.kt`): `insertOrUpdate`,
`insertDataFromPendingMessages`, `getAllMessagesTimeStampFromDB`, `getLastMessageTimeStamp`,
`insertOrUpdateFromApi`, `updateMessageStatusInDataBase`, `getChatWithUser` (paged read),
`deleteItem`, `getPendingMessages`, `updateMessageStatusToPending`, `updateLocalUniqueId`,
`getFileDataWithUniqueIdOfMedia`, `updateThumbId`, `updateUploadingError`.

`sorting_activity` on the person and the room is the recent-list order key, written on every
send and receive (`UsersDB.kt:171`, `ChatRoomDb.kt:152`).

---

## 5. Offline queue

`offline/OfflineChatQueue.kt` + `PendingChatWorker.kt`. A one-time network-constrained
WorkManager job named `pending_chat_flush`, plus a 15-minute periodic backstop. Rows with
`status = 0` and a body are eligible. Scheduled on send-while-disconnected and on app
background.

---

## 6. Badges

`ChatSocketHelper.updateGroupOrPrivateChatBadges` (line 1116) switches on `chat_tool` and
calls the shared badge handler with `Chat` / `Group` (or the Budget variants), `Add` on
receive and `ReadAll` on read.

---

## 7. What v2 gets wrong — do not copy

1. **Encryption — corrected.** An earlier reading of this audit was wrong. The dead
   `decryptChatMessage` in `ChatSocketHelper.kt:763` is commented out, but it is **not** the
   live path. The real ones are `ChatAndGroupVM.addChatMessage` (lines 470–495), which
   encrypts `message`, `message_translation` and an attachment's `caption` on send, and
   `HoldersViewhandler` (lines 654–671), which decrypts on bind. Every chat in the app sends
   ciphertext. **v3 does the same**, with one improvement: it decrypts once on the way into
   storage rather than on every bind, so Realm holds readable text and search matches words
   instead of hex.
2. **Typing never ends.** `emitForChatTyping` throttles to one `start` a second and has no
   caller for its `End` branch, so every other client waits out the 4-second timeout.
   Already fixed in v3's `TypingController`.
3. **Presence blinks on rotation.** `markUserOnline` is called from an activity callback, so
   a rotation marks the user offline and online again. v3 drives it from the process
   lifecycle instead.
4. **One shared Firebase presence listener.** `FireBaseRealTimeDataBase.dataListener` is a
   single field, and each new observer removes the previous one, so only the last screen to
   ask ever shows a presence dot. v3's `PresenceRepository.observe` is a cold flow per row.
5. **Location is sent as `lat` / `long`, not `latitude` / `longitude`.** The asymmetry is
   the server's. v3's unit-chat DTO used the symmetrical spelling, which serialises to keys
   the backend ignores, so a pin was accepted and came back empty everywhere.
6. **Contact sharing was never implemented.** v2 shows the tile and its contact-reading code
   in `MediaExtension.kt` is commented out end to end, so picking a contact has always done
   nothing. There is no contact message type on the wire; v3 sends name and number as text.
7. **Disappearing messages and file cabinet** — confirmed with you as not wanted. v2 shows
   the disappearing row only inside a personal project; C&C has no file cabinet at all.

---

## 8. Implementation checklist

Ordered so each step is testable on its own.

### Transport
- [x] `CncSocketDataSource` — typed emit/observe over the shared `SocketManager` for every
      event in §1, on both `ChatSurface` values
- [x] Acknowledgement handling: server id and `status = 1` persisted from the ack
- [x] 30-second in-flight guard keyed on `unique_id` (§1.3)
- [x] Reconnect resync: `pending-messages:get` with the newest local timestamp
- [x] Typing emit and receive — `TypingController`
- [x] Presence emit and read — `PresenceRepository`
- [x] `CncRealtime` — every subscription in one place, started with the project

### Storage
- [x] `CncMessageEntity` in Realm, keyed `unique_id`, plus attachment/reply/reaction/element
      children; schema 24 → 25
- [x] `CncConversationEntity` (person or room) carrying `sortingActivity`, `unread`,
      `favourite`, `deviceId`
- [x] Realm-first reads so a thread opens offline

### Repository
- [x] `CncRepository` — send, reply, edit, delete, react, mark read, history
- [x] REST history for both surfaces (§3)
- [x] Optimistic insert then reconcile on ack
- [x] Read watermark applied to the whole run, not just the named message
- [ ] Attachment sends routed through the existing `UploadQueue` rather than a second queue

### Lists
- [x] Recent list from `user:list`, merged with the project directory — `CncDirectory`
- [x] Ordering and membership rule — `RecentChatOrder`
- [x] Favourites and block/unblock
- [ ] Access rights (who may reach whom)
- [ ] Call log read and clear

### Groups
- [x] Create, rename, add/remove member, edit admin, leave — `CncGroupRepository`
- [ ] Change group picture (needs the upload path)
- [ ] `chat-room:create` / `remove` / `updated` applied live

### Thread
- [x] `CncThreadScreen` reads the repository; the sample data is deleted
- [x] Read watermark emitted on open
- [ ] Read watermark on scroll to bottom
- [ ] Badge add on receive, read-all on open
- [ ] Group read counts ("Read by 11") — needs the `group-chat/readby` fetch

### Budget
- [ ] Second `ChatSurface` value proved end to end against the same repository
