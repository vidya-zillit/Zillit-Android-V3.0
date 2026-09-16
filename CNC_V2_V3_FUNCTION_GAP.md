# C&C — complete v2 vs v3 comparison

Every class and function in v2's C&C packages, checked against v3. Built by enumerating both
sides, not by memory. This supersedes the phase plan's "done" marks where the two disagree —
**the phases were about structure; this is about coverage.**

Your three reported bugs are all in §6, with causes.

---

## 1. Socket layer — `ChatSocketHelper` (v2) → `CncSocketDataSource` + `PresenceRepository`

| v2 function | v3 | Notes |
|---|---|---|
| `sendMessageOnSocket` | ✅ `sendMessage` | |
| `emitForReadChatMessage` | ✅ `markRead` | |
| `emitForDeleteChatMessage` | ✅ `deleteMessages` | |
| `emitForChatReaction` | ✅ `react` | |
| `emitForMessageEdit` | ✅ `editMessage` | |
| `emitFotChatTyping` | ✅ `TypingController` | v3 also sends `end` |
| `getUserListOfChattedUser` | ✅ `recentConversations` | shape fixed today |
| `getAllChatMessages` | ✅ `requestMissedMessages` | |
| `markUserOnline` / `markUserOffline` | ✅ `PresenceRepository` | |
| `markChatSendInFlight` | ✅ `claimInFlight` | |
| `listenForEvents` | ✅ `CncRealtime` | |
| **`sendMessagesForDoubleTick`** | ✅ **FIXED** | §2.1 |
| **`updateGroupOrPrivateChatBadges`** | ✅ **FIXED** | §2.2 |
| **`addPendingMessagesStatus` / `removePendingMessagesStatus` / `flushPendingMessages`** | ✅ **FIXED** | §2.3 |
| `updateMessageStatusInList` | ✅ already covered | §2.4 |
| `callBadgesApiOnMessageDelete` | ➖ dead code in v2 | §2.5 |
| **`getChattedUsersAndGroupBudget`** | ✅ **FIXED** | §2.6 |
| `handleContinuousChecks` | ➖ n/a | v3's `SocketManager` owns reconnection |
| `getActiveCallList` | ⏸ deferred | calling |
| `decryptChatMessage` | ✅ in the mapper | decrypt-on-write |

## 2. What those missing ones actually cost

### 2.1 `sendMessagesForDoubleTick` — **delivered ticks never appear**
v2 emits `status = 2` when a message notification arrives, telling the sender it reached this
device. v3 never emits it, so **no message ever reaches Delivered**. A sent message shows one
tick until the recipient opens the thread, then jumps straight to Read. The `SendState.Delivered`
branch in the bubble is unreachable.

### 2.2 `updateGroupOrPrivateChatBadges` — **badges only ever clear**
v3 clears a badge on open, but nothing **adds** one on receive. Counts come from the badge
tree, which is fed by push notifications — so a message that arrives over the socket while the
app is open raises no count at all. Phase 8 built half of this.

### 2.3 Pending read-receipt queue — **read receipts lost when offline**
v2 queues read receipts that could not be emitted and flushes them on reconnect. v3 fires and
forgets, so a thread read on a dead connection stays unread for the sender forever.

### 2.4 `updateMessageStatusInList` — **not a gap, checked**
I had this wrong. It is not a reconnect sweep: its only callers are the **read-until**
handlers, promoting the sender's messages to the status just received. v3's `applyReadReceipt`
already does that, and more precisely — it applies to messages at or before the watermark,
where v2 promotes every message in the list.

### 2.5 `callBadgesApiOnMessageDelete` — **not a gap, checked**
Also wrong. The function's body is **commented out end to end** in v2; it takes its argument
and does nothing. There is nothing to port.

### 2.6 Budget recent list — **different response shape, not just a prefix**
C&C's `user:list` returns `detail.usersList` (ids). Budget's `budget:recent:list` returns
`detail: [ChatRooms]` — **whole room objects**. v3 decodes both with the C&C model, so the
Budget list would come back empty. Phase 11 verified the event *names* and missed this.

---

## 3. Thread — `ChatAndGroupVM` (v2) → `CncThreadViewModel`

| v2 | v3 | |
|---|---|---|
| `addChatMessage`, `sendMessageOnSocket`, `sendReplyMessageOnSocket`, `sendEditMessage` | ✅ | |
| `updateReaction`, `emitForChatTyping`, `deleteChatMessages` | ✅ | react/typing/delete wired |
| `getChatListFromApi`, `callApiForGettingMessages`, `checkForLoopApiLooping` | ✅ `loadHistoryPages` | pages until a short page, capped at 20 |
| `handleMultiSelect` / `handleMultipleSelectionAction` / `showMultiSelect` | ✅ | long-press menu, multi-select, delete with confirmation |
| `handleAudioPlaying` | ✅ `toggleAudio` | local file, cache, then download |
| `setSearchIndex` / `checkForSearch` / `setSearchCount` / `scrollUpAndDown` | ✅ | local match over what is loaded, next/previous |
| `maintainAttachmentList` | ✅ `attachments` | derived from stored messages, not a second fetch |
| `highLightRepliedBackground` | ✅ `jumpToQuoted` | within the loaded page; reports failure rather than doing nothing |
| `getChatRoomDetail` | ➖ | the room list already carries every field; a single-room fetch would only duplicate it |
| `redaByUserResponse*` | ✅ `readers` | counts and the names behind them |
| `uploadingDataMapper` | ✅ | upload queue |
| `callApiForArchiveMessage`, `fileCabinetMessages` | ➖ | removed at your request |

## 4. Lists — `UserAndGroupListHandler`, `MembersVM`, `GroupsVM`

| v2 | v3 | |
|---|---|---|
| `updateFavUserInUserListAndDb` | ✅ `toggleFavourite` | |
| `updateBadgesOnChatUser` / `OnGroups` | ✅ | badges rise on receive (§2.2) and the list reads them per conversation |
| `updateLastUpdateTime*` | ✅ `touchConversation` | |
| `updateBlockUnBlockStatus` | ✅ `refreshBlocks` | |
| `updateDeletedMessage` | ✅ `refreshPreview` | recomputed from what is still visible, not patched |
| `searchList`, `searchUserList`, `searchFavList` | ✅ | local filtering |
| `blockUnBlock`, `editAdmin`, `leaveChatRoom`, `getChatRooms` | ✅ | |
| `deleteChatRoom` | ✅ `delete` | owner only, with a confirmation |
| `getActiveCalls` and friends | ⏸ | calling |

## 5. Calls — `RecentMissedVM` → `CallLogRepository`

| v2 | v3 | |
|---|---|---|
| `getCallLogs` | ✅ | paged, cached per filter |
| `deleteCallLogs` | ✅ | |
| `continueNextPagination` | ✅ `loadMoreCalls` | cursor-advance guard, fetches 5 rows before the end |
| `searchList` | ✅ | matches over every page loaded, on the other party's name |
| badge mark-read on the Missed tab | ✅ `markCallsRead` | clears `call_label` on opening Calls or Missed |

## 6. Your reported bugs, with causes

### 6.1 "Call log takes time to show all data" — **fixed**
Only the first page was fetched. It now pages as you scroll, starting five rows before the
end so the round trip is hidden. Two stop conditions carried from v2: a short page is the
last one, and a cursor that did not advance means the server's inclusive boundary handed back
the same page — without that second check it would re-request forever.

### 6.2 "After switch takes time to update list" — **fixed**
Each filter's pages are now kept, so switching back shows what was already fetched with no
network call at all. Only the first visit to a filter asks.

### 6.3 "Count shows wrong" — **fixed**
The chip now shows the server's `totalRecords` rather than counting the page in hand. The
unread chip's related half was §2.2, also fixed: badges rise as well as fall.

### 6.4 "Chat not loading on page"
Two causes. The recent-list shape was wrong (fixed today — it returns ids, not objects, and I
had modelled it as objects, so every conversation sorted as though it had no history and was
filtered out). Second, `loadHistory` fetches **one page with no paging loop**; v2's
`checkForLoopApiLooping` keeps fetching until the window is filled.

---

## 7. Fix order

Section 2 is **done**. Remaining:

Sections 2, 3, 4 and 5 are **done**, and §6.1–6.4 with them. Remaining:

1. Group picture upload (Phase 7's remainder)
2. Share hand-off to the OS chooser (the selection is built; the Intent is the screen's job)

---

## 8. Honest note on the phase plan

The phases tracked **structure** — is there a transport, a repository, a wired screen. They did
not track **coverage** against v2 function by function, which is why they all read "done" while
the list above exists. This document is the coverage view; the phase plan stays useful for
sequencing, but this is the one to judge completeness by.
