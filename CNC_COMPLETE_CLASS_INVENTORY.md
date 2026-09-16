# C&C — complete class and function inventory, v2 vs v3

Every file, class and function in v2's two C&C packages, enumerated mechanically and checked
against v3. Nothing summarised away.

This is the **third** pass and it found things the first two missed, listed in §0.

Legend: ✅ done · ⚠️ partial · ❌ missing · ➖ deliberately not carried over · ⏸ deferred

---

## 0e. Seventh pass — asking the toolchain instead of asking myself

Every pass so far was me reading code I had written, which is the weakest possible check for
the mistakes I am prone to. This one handed the question to tools that have no stake in it:
Android Lint over the whole app, and the Kotlin compiler's own warnings, which every build
in this session had been filtering out.

Three real defects, two of them in code written during this session:

**`MediaMetadataRetriever.use { }` fails on Android 9.** `use` needs `AutoCloseable`, which
that class only implements from API 29, and this app's minimum is 28. Both call sites wrap
themselves in `runCatching`, so the failure was swallowed: on Android 9 every video
attachment arrived with no thumbnail, no duration and no dimensions, and nothing said why.
Replaced with an explicit `release()` in a `finally`, which works on every supported version.
This sits in the attachment path C&C uses.

**`MediaStore.Downloads` is an API 29 class**, referenced inside a method whose version
guard lived at the call site. The guard works, but nothing in the method said so — the
compiler could not check it and the next reader had to take it on trust. Now annotated.

**Deprecated non-mirrored icons.** Chat and file icons that point the wrong way in a
right-to-left language, in an app that ships multi-language. Swapped for the auto-mirrored
versions.

Also verified, because it is a runtime failure the compiler cannot catch: every entity the
code queries, and every embedded class referenced as a field type, is registered in the
Realm schema. All twenty-six are.

Lint errors went from twelve to nine. What is left is outside C&C and each is either
pre-existing and intentional — the notification post is deliberately wrapped and commented
— or a known false positive: the two `produceState` reports fire on code that does assign
`value`, and the `RestrictedApi` reports are on the standard navigation API.

---

## 0d. Sixth pass — auditing what happens when things fail

Earlier passes all asked what happens when things work. This one asked the opposite: where
does a user action abort, fail, or throw, and what is the person told. Nothing here is
invisible to a reader of the code — it is invisible to every *check* run so far, because the
function exists, is called, and populates its fields. It just does nothing when the server
says no.

**Every group administration action failed silently.** Rename, make admin, remove member,
change photo, leave, delete, block, favourite, edit a message, delete messages: all of them
discarded the boolean the repository returns. The dialog closed, the list did not change,
and nothing said why. A refusal was indistinguishable from a slow connection, and the only
way to find out was to try again. All of them now report, and every route shows it. Silence
on success stays: the list changing is the confirmation.

**Four taps could do nothing without explaining.** Opening a document, sharing an
attachment, saving one, and tapping a quotation whose original is further back than the
thread has loaded. The unit chat already said so for the first three; C&C did not.

**And one structural defect this method was made to find.** The reconnect resync wrapped six
independent steps in a single guard, so the first to throw cancelled the other five — a
failed catch-up request silently took the pending flush, the recent list, the ordering, the
block list and the queued read receipts with it, on every reconnect, and the log named none
of them. The conversation-list refresh had the same shape over five steps. Each step is now
guarded on its own and names itself in the log. These are independent pieces of catching up;
one being unavailable says nothing about the rest.

---

## 0c. Fifth pass — auditing state fields rather than functions

Every earlier pass looked at code: which functions exist, which are called, which callbacks
are empty. This one looked at **data**. For every field in every C&C state model, two
questions: does anything assign it, and does anything read it. A field nobody assigns is a
feature stuck at its default; a field nobody reads is one that never reaches the screen.
Both are invisible to a function-level check, because the functions on either side exist and
are wired.

Three defects, all of them things a person would notice:

**Edit did nothing.** `CncThreadUiState.editing` was never assigned. The option was offered,
`startEdit` selected the message, `saveEdit` was wired — and the composer never entered edit
mode, because the one field that puts it there was tracked inside the view model and never
published. There was no way to type the change.

**The list tabs never showed a spinner.** `CncUiState.loading` was hardcoded `false`, and
all three tabs read it to choose between a spinner and their empty state. On a cold start,
while the directory merge and the recent-list fetch were still running, Chat, Calls and
Contacts all said there was nothing here. "Nothing yet" and "still arriving" are different
answers and the list was giving the wrong one.

**Replies arrived with no quotation.** `ChatFeedItem.Post.replies` is never populated for
C&C, and that turns out to be correct rather than a bug: a socket chat's reply is an
ordinary message carrying a quote, where a unit chat's reply really is a comment hanging off
a post. Both shapes are real. But nothing rendered the quote, so a reply looked like an
unrelated message. `ChatMessage.quoted` now carries it, built from the copy stored on the
message rather than by finding the original — which may be older than anything loaded, or
deleted since.

**And a correction to the pass before.** The fourth pass deleted `jumpToQuoted` on the
reasoning that v3 nests replies under their parent, so there was nothing to jump to. That
reasoning was wrong: C&C replies are separate messages. The quotation is now a control, and
tapping it scrolls to what it quotes and tints it, which is what v2 does.

---

## 0b. Fourth pass — checked by method, not by re-reading this file

The first three passes asked "does v3 have a function for this". That question kept
answering yes for things that were built and never connected, because a function exists
whether or not anything calls it. This pass asked three different questions and each one
found something the others could not:

**Which callbacks are still empty at the call site?** Seven were, and only two of them were
calling: tapping a shared pin did nothing, the block banner's Unblock did nothing, a group
created with a photo lost the photo, and "where is this person" was a dead control on both
the contact row and the profile. The last needed three fields nobody was storing
(`show_location` and `last_location`), which is schema 33.

**What does v3 define that nothing calls?** Two things. `jumpToQuoted` implements v2's
"scroll to the quoted message", which v3 cannot have: v2 renders a thread flat so a reply
carries a quotation card, while v3 nests replies inside the post they answer, putting the
original directly above. There is nothing to tap. It is removed, with the reason recorded
where it used to be. `sendReply` was a second name for `sendMessage`.

**What does v3 offer that v2 never had?** A Mute row, which had no endpoint behind it —
v2 has the field and every line that would use it commented out. Removed rather than left
as a switch that changes nothing.

Also corrected from the pass before: five entries in the long-press menu were shown and
handled by an `else -> Unit`, so Copy, Save, Gallery and Forward silently did nothing;
Image Reply is now withheld rather than shown, because its editor is wired for the unit
chat only. And Save downloaded into the app's own directory, where no file browser can
reach it — it now copies into the device's Pictures, Movies or Downloads.

---

## 0. New in this pass — not in either earlier document

| Gap | Why it matters |
|---|---|
| **@mention tagging** | `checkForTagging`, `checkOrHighLightTaggedUser`, `checkUserAlreadyInTaggedList`, `filterUsersFromList`, `onTaggedUserClick`, `decodeTaggingChatMessage`, `TaggingMembersAdapter`. Typing `@` offers members, the message stores `message_elements`, and the rendered text highlights and links them. v3 **stores** `messageElements` and never reads or writes them. |
| **Emoji reactions UI** | `handleEmojiReactionView`, `onEmojiViewClick`, `provideEmojiString`, `getEmojiByUnicode`. v3 has `react()` in the repository with **nothing calling it** and no reaction row on a bubble. |
| **Departments filter** | `setUpChatTabOptions` builds **six** chips — All, Unread, Members, Groups, Favourites, **Department**. v3 has five. Department appears only when a system-defined group exists. |
| **Filter gating rules** | Groups is hidden while the project is PENDING; Favourites is hidden for personal projects and PENDING. v3 always shows all five. |
| **Read more** | `applyReadMore` truncates a long message with a "read more". v3 renders the whole thing. |
| **Phone numbers as links** | `containsPhoneNumber` linkifies numbers in a message. v3 does not. |
| **Chat library** | `ChatLibraryActivity` + `ChatLibraryAdapter` + `LibraryItem` — media / documents / links in three tabs. v3 has `ChatLibraryScreen` **built but unreachable from C&C**. |
| **Search users to start a chat** | `SearchUsersPage`. v3 searches within the lists it already shows; there is no "find someone not yet in the list". |
| **Call details sheet** | `CallActivityDetailSheet` — participants, attendance, durations, join/leave counts. v3's `onCallDetails` is empty. |
| **Group member management UI** | `GroupMembersListAdapter`, `DeleteChatUserAdapter`, `onUserLongPress` → make admin / remove. v3 has the **repository** calls (`setMembers`, `setAdmin`) with no screen driving them. |

---

## 1. `chatAndGroupChat/` — file by file

### ChatSocketHelper.kt — `object ChatSocketHelper`
Covered fully in `CNC_V2_V3_FUNCTION_GAP.md` §1. All ✅ or accounted for.

### ChatAndGroupVM.kt — `class ChatAndGroupVM`
Covered in that document §3. All ✅ except `getChatRoomDetail` (➖, the room list already
carries every field).

### ChatAndGroupPage.kt — `class ChatAndGroupPage` (2,999 lines, the thread UI)

| Function | v3 |
|---|---|
| `initView`, `initCall`, `handleObservers`, `onCreate/Pause/Resume/Destroy` | ✅ Compose lifecycle |
| `handleActionViews`, `showHideActionView`, `handleBottomSheetActions` | ✅ options sheet |
| `handleAttachmentData` | ✅ picker |
| `handleAudioView`, `onAudioClick`, `resetAudioView` | ✅ `toggleAudio` |
| `handleBack`, `handleOnBackPressed`, `removePreviousScreen` | ✅ navigation |
| `handleChatClickEvents`, `onClickListener`, `onLongPressed` | ✅ |
| `handleDateHeader` | ✅ day chip |
| `handleEditMessage` | ✅ `startEdit` |
| `handleName`, `handleProfileImage`, `updateGroupData` | ✅ header |
| `handleReplyMessage`, `showReplyUI`, `onReplyMessageClick` | ✅ |
| `handleRetryMessages`, `onRetryPress` | ✅ retries the queue row or the pending message, whichever it is |
| `handleScrollButton`, `scrollToIndex`, `scrollToMessage` | ✅ |
| `handleSearchViews`, `showSearchView` | ✅ |
| `handleSendAndRecordButton` | ✅ composer |
| `handleTypingOrLastUpdatedOn` | ✅ |
| `setCacheMessage` | ✅ drafts |
| `onMultiSelectedMessageClick`, `showMultiSelect` | ✅ |
| `onCancelUploadingPressed` | ✅ cancel beside the percentage; drops the optimistic row with it |
| `onDownloadDone`, `handleGalleryViewerPermissionOptions` | ✅ Save copies into the device's own Pictures/Movies/Downloads via `DeviceSaver`; the API-28 permission is asked for by `SaveToDeviceLauncher` |
| **`checkForTagging`, `checkOrHighLightTaggedUser`, `checkUserAlreadyInTaggedList`, `filterUsersFromList`, `onTaggedUserClick`** | ✅ **@mentions** — suggestion list, `@<userId>` on the wire, names in `message_elements` |
| **`handleEmojiReactionView`, `onEmojiViewClick`** | ✅ **reactions UI** — six-emoji row above the long-press actions |
| `handleJoinCallVisibility`, `liveKitActiveCallForRoom`, `showAlreadyJoinDialog`, `handleIncomingData` | ⏸ calling |
| `optionHandler`, `optionClickWithItem` | ✅ |
| `onMemberClick`, `onEditGroupPressed` | ✅ group info |
| `onScrolled`, `onScrollStateChanged` | ✅ paging + read-on-bottom |
| `removeDataOnDestroy` | ✅ |

### ChatAndGroupAdapter.kt / adapter/ChatAndGroupDiffAdapter.kt
RecyclerView plumbing — `onBindViewHolder`, `getItemViewType`, `areItemsTheSame`,
`provideItemType`, `provideMessageOrientation`, diffing. ➖ **All replaced by Compose**, which
is the point of the rewrite. The behaviour they carry is listed under the page above.

`handleHighLightForSearch`, `handleSearch` ✅ · `updateUnreadHeader` ✅ ·
`maintainTimeStampOfTopAndBottomOfList` ✅ · `downloadingProgress`, `uploadingPercentageProvider`,
`uploadDone`, `uploadingError` ✅ via the upload queue.

### viewholders/HoldersViewhandler.kt

| Function | v3 |
|---|---|
| `decryptChatMessage`, `decodeReplyChatMessage` | ✅ decrypt-on-write |
| `handleAudio`, `handleImageClickListener`, `handleDateHeader` | ✅ |
| `isMineMessage`, `provideReceiverUserNameAndProfileOPic` | ✅ |
| `handleEmojiView`, `provideEmojiString`, `getEmojiByUnicode` | ✅ chips under the bubble, tallied per emoji |
| **`applyReadMore`** | ✅ was already there; now truncates after names are substituted |
| **`containsPhoneNumber`** | ✅ phone numbers and web addresses are tappable |
| **`decodeTaggingChatMessage`** | ✅ substituted at render, falling back to the sent name |

### viewholders/MineViewHolders.kt, OtherViewHolder.kt
Seven view holders per side (image taller / wider / square, audio, reply, message, grid).
➖ **One Compose bubble replaces all fourteen** — the aspect-ratio variants are layout, not
behaviour.

### ChatListHander.kt — `class ChatListHandler`
`addItem`, `updateItem`, `updateItemList`, `setChatList`, `deleteChatItems`, `showMessages`,
`showMultiSelect` ➖ replaced by Realm observation. `setTypingData` ✅ ·
`updateSocketConnectionInFlow` ✅ `SocketManager.connectionState`.

### userOrGroupListhelper/UserAndGroupListHandler.kt
All ✅ — see `CNC_V2_V3_FUNCTION_GAP.md` §4. `updateDeletedMessage` ✅ as `refreshPreview`.

### offline/OfflineChatQueue.kt + PendingChatWorker.kt
`scheduleFlush`, `schedulePeriodicFlush`, `hasPendingTextMessages`, `doWork`, `awaitCondition`
✅ — v3's `UploadQueue` + `UploadWorker` cover all of it, with parallel uploads on top.

### library/ — `ChatLibraryActivity`, `HomeChatLibraryActivity`, `ChatLibraryAdapter`, `LibraryItem`
`loadAll`, `queryAndSplit`, `scanList`, `setupTabs`, `showTab`, `tabLabel`,
`refreshTabLabels`, `openMediaViewer`, `openDocExternally`, `openLinkExternally`, `onRowClick`
✅ **Reachable from C&C** two ways: the thread header, and Media / Files on either
details screen. The thread's
`attachments` flow is built; nothing opens a library screen with it.

### SearchUsersPage.kt — ✅ covered by the Contacts tab
### ProfileShowingActivity.kt / ProfileVM.kt
`initUi`, `handleObserver`, `updateList`, `onUserLongPress`, `handleLongPressOptions`,
`openMediaPickerForGroupProfile`, `uploadFileDone`, `callRoomApi`, `onEditGroupPressed` —
✅ profile and group info exist, with **member long-press (make admin / remove) and group photo
upload do not**. `handleDeleteLeaveOrBlockOption` ✅ · `handleDisappearingBottomSheet`,
`setDisappearingText`, `postDisappearingMessage` ➖ removed at your request.

### model/ and messageTypeHander/
25 wire models — all ✅ represented in `CncDto.kt` except `FileCabinetReqModel` and
`DisappearingSettings*` (➖ removed). `MessageType`/`MessageOrientation` ➖ Compose handles it.

---

## 2. `bottomNav/chatAndCall/` — file by file

### ChatAndCall.kt, TabViewAdapter.kt
`initTabs`, `initTabLayout`, `setBadges`, `listenForBadges`, `onPageSelected`,
`progressListen`, `selectTab`, `setBadgesOnTab` ✅ — v3's `CncTabs` and badge wiring.

### viewModels/MembersVM.kt

| Function | v3 |
|---|---|
| `listenForUserList`, `listenForGroupAndUserListingChanges` | ✅ Realm observation |
| `searchList`, `searchUserList`, `searchFavList`, `searchOrSubmitUserGroupList` | ✅ |
| `makeOrRemoveFavourite` | ✅ |
| `listenForTabClick` | ✅ |
| **`setUpChatTabOptions`** | ✅ six chips, gated by project type and membership status |
| `getActiveCalls`, `listenForCall`, `refreshActiveCallsDebounced`, `emitActiveCallsRefreshed`, `onActiveCallsRefreshed` | ⏸ calling |

### viewModels/GroupsVM.kt — all ✅
`getChatRooms`, `blockUnBlock`, `editAdmin`, `leaveChatRoom`, `deleteChatRoom`, `searchList`.

### viewModels/RecentMissedVM.kt — all ✅
`getCallLogs`, `continueNextPagination`, `deleteCallLogs`, `searchList`, `timeStamp`, `callLogs`.

### viewModels/ContactVM.kt — ✅ `searchList`

### groups/CreateGroupPage.kt + CreateGroupVM.kt

| Function | v3 |
|---|---|
| `createRoom`, `createOrUpdateChatGroup`, `setRoomData` | ✅ |
| `removeUserFromGroup` | ✅ long press a member on the group screen |
| `getChatRoomDetail` | ➖ |
| **`updateGroupProfilePicture`, `openMediaPickerForGroupProfile`, `uploadFileDone`** | ✅ picker, S3 upload, then the room update |
| `handleEditGroupData`, `handleIncomingData` | ✅ rename from the group screen; the member picker takes a `roomId` and adds to it |
| `selectMemberPage`, `onDeleteMemberClick` | ✅ the same picker adds to an existing group; removal is a member long press |

### fragment/ — the tab bodies

| File | v3 |
|---|---|
| `ChatFragment` | ✅ `ChatTabScreen` |
| `MembersFragment` | ✅ Members filter |
| `ContactsFragment` | ✅ `ContactsTabScreen` |
| `ChatFavouritesFragment` | ✅ Favourites filter |
| `GroupFragment` | ✅ Groups filter and `onLongPress` (leave / delete, gated as v2 gates them). `onJoinNowClick` / `showAlreadyJoinedDialog` ⏸ calling |
| **`DepartmentFragment`** | ✅ the Departments chip |
| `CallFragment`, `RecentCallFragment` | ✅ Calls tab; `observeOngoingCalls`, `onJoinCallClick`, `onReturnToCallClick` ⏸ |
| `CallActivityDetailSheet` | ✅ `CallDetailsSheet` — participants and how each of them fared |
| `GuestJoinSheet`, `OngoingCallSheet` | ⏸ calling |

### adapter/ — all ➖ replaced by Compose
`ChatAndGroupListingAdapter` (`handleBadge`, `handleLastMessage`, `updateFavourite`,
`profilePicHandler` ✅ as row composables; `handleMediaSoupCall`, `handleMediaSoupJoinBtn` ⏸),
`ChatMembersAdapter`, `CallLogsAdapter`, `GroupListAdapter`, `GroupMembersListAdapter` ➖
(RecyclerView plumbing; the rows themselves are composables), `DeleteChatUserAdapter` ➖,
`OngoingCallsAdapter` / `OngoingParticipantsAdapter` / `OngoingBlockAdapters` ⏸,
`CallLogsHolder` (`adHocGroupTitle` ✅ — an unnamed group call is titled from its
participants: two names, then a count).

### models/ — all ✅ except `GetSingleRoomDetail`/`RoomData` (➖) and `GetChatUsersAndGroups` (✅).

---

## 3. Ranked remaining work — all closed

**Visible on every message**
1. ~~Emoji reactions~~ — tallied in the feed mapper, drawn as chips under the bubble,
   toggled by tapping a chip, picked from v2's six emoji above the long-press actions. Only
   offered once the server has acknowledged the message, because a reaction addresses its
   server id.
2. ~~@mention tagging~~ — typing `@` in a group offers its members; the body goes out with
   `@<userId>` and the names travel beside it in `message_elements`, so a client that has
   never seen the person still renders a name. Substituted back at render, highlighted, and
   falling back to the name it was sent with for somebody who has since left.
3. ~~Read more; phone numbers as links~~ — read-more was already there. Web addresses and
   phone numbers are now tappable, detected conservatively so ordinary text is never turned
   into a control.

**Visible on every list**
4. ~~Departments filter and the gating rules~~ — a sixth chip, showing the rooms that carry
   a department id and removing them from Groups. Chips are decided per project: no
   Departments or Favourites in a personal project, and neither Groups nor Favourites for a
   member still pending. Ad-hoc call rooms and a personal project's implicit General room
   are excluded from the list entirely.
5. ~~Ad-hoc group call titles~~ — a group call with no room name is titled from its
   participants: two names, then a count.

**Whole screens**
6. ~~Edit an existing group; group photo; member admin and removal~~ — a pencil beside the
   name opens a rename dialog; the camera badge opens the picker and uploads to S3 before
   announcing the new photo; long-pressing a member offers admin and removal, to admins
   only and never on yourself.
7. ~~Chat library reachable from a conversation~~ — from the thread header as an overlay,
   and from Media / Files on either details screen as its own destination, opening on the
   tab that was asked for. Backed by the thread's own feed rather than a second derivation.
8. ~~Call details sheet~~ — who was on a call and how each of them fared, which is the
   question a "Group call" row cannot answer.
9. ~~Search users to start a new chat~~ — already covered by the Contacts tab, whose search
   now matches designation as well as name.
10. **Group join / already-joined** — this is the guest **call** invite sheet and the
    "already joined from another device, switch here?" prompt, both of which live in v2's
    calling code. Deferred with the rest of calling.

**Smaller**
11. ~~Cancel an upload in flight~~ — the percentage row carries a cancel control, which
    stops the transfer and takes the optimistic row out of the thread with it.
12. ~~Retry a failed message~~ — a queued file that gave up and a text message the server
    never acknowledged both show the retry prompt, and each is retried by whichever of the
    queue and the repository owns it.

**Deferred by you:** everything calling-related.

---

## 4. Why three passes were needed

The first pass compared **modules**, the second compared the **main classes**, and this one
enumerated **every file**. Each pass found real gaps the previous had not, because a summary
of a 3,000-line screen hides features rather than listing them. The items in §0 were all
inside files I had described but not enumerated.
