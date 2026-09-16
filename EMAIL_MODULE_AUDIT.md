# Email — what v2 does, end to end

Audit of the **new** email module in v2 (`bottomNav/new_email`), read file by file ahead of
the v3 rebuild. 78 Kotlin files, ~16,100 lines, ~31 layouts, 124 string keys.

The legacy `bottomNav/mailing` package (33 files, ~12,800 lines) is **not** dead: see §1.

There is no design document for any of this except the rules engine, and that one opens by
declaring its own editor section superseded. **The code is the specification.**

---

## 1. Two email modules, two entry points

| Shell | Module | Reached by |
|---|---|---|
| `BottomNavigationActivity` (approved users) | **new** — `NewEmailLandingPage` | Email tab |
| `PendingUserDashboard` (join still pending) | **legacy** — `mailing.EmailFragment` | Email tab |

The pending-user path was never migrated. The new module also still reaches into the legacy
one in three live places:

- **BCC Presets** in the new settings screen launches legacy `mailing.views.EmailPresetPage`
- **`SendQueueManager`** is built on legacy `EmailMessageModel` / `EmailAttachment` and writes
  to the legacy `MailingDB` — the entire send pipeline is shared
- **`mail_credentials_vw.xml`** + `RevealablePasswordBinder` are shared with `mailing.EmailFragment`

Plus three cross-tool dependencies: **Drive** (rules' folder picker), **docSign/docusign**
(link handling in the trail), and **forms_and_signature**'s `CustomAutoCompleteAdapter`
(compose recipient autocomplete).

---

## 2. The screen list

**Screens (11)**

| # | Screen | Class | Entry |
|---|---|---|---|
| 1 | Landing shell (drawer + nav host) | `NewEmailLandingPage` | Email tab |
| 2 | Email list | `EmailListFragment` | start destination, arg `folderName` (default `INBOX`) |
| 3 | Email detail / thread | `EmailDetailActivity` | row tap |
| 4 | Compose | `ComposeActivity` | FAB, reply/forward, drafts, external share |
| 5 | Search | `SearchActivity` | toolbar |
| 6 | Folder picker (move) | `FolderSelectionActivity` | row menu, selection bar, detail |
| 7 | General settings | `GeneralSettingsActivity` | drawer |
| 8 | Signatures list + editor | `SignatureListActivity`, `EditSignatureActivity` | settings |
| 9 | Email groups list + editor | `EmailGroupsActivity`, `EditEmailGroupActivity` | settings (**admin only**) |
| 10 | Contacts list + editor | `ContactListActivity`, `EditContactActivity` | drawer |
| 11 | Calendar wrapper | `EmailCalendarActivity` | drawer |

**Rules sub-module (3)** — `EmailRulesActivity`, `EditEmailRuleActivity`, `RuleExecutionsActivity`

**Sheets / drawers (5)** — folder drawer (`FolderDrawerFragment`), filter
(`FilterBottomSheetFragment`), choose mailbox (`bottom_sheet_choose_mailbox`), email
forwarding (`bottom_sheet_email_forwarding`), mail credentials (`mail_credentials_vw`),
Drive folder picker (`RuleDriveFolderPickerSheet`).

---

## 3. The three architectural keystones

### 3.1 Read state is the badge tree, not a flag
`CommonBadgesHandler` holds one badge row per unread email — `unit` = folder, `level_1` = uid,
`level_2` = id, `level_3` = mailbox address. The Email tab badge is
`emailTotal = emailBadgeList.size` (a row count; the summing version sits commented out).

`EmailReadState.isRead(folder, uid, id, storedRead)`: for every folder **except Sent, Trash
and Junk**, an email is unread iff such a row exists. Those three fall back to the stored flag.
An orphaned badge naming a deleted folder is accepted for INBOX only.

Consequences the rewrite inherits:
- `markAsRead` writes **Realm only — there is no API call**. Read state propagates purely
  through badges, which is how multi-device read works without an IMAP flag.
- Realm does not change when badges change, so the list re-maps rows on every badge emission.
- v3's path-keyed badge store must model email rows before the list can render correctly.

### 3.2 There is no pagination
`syncFolder` fetches the folder's **complete** uid list, diffs it against Realm, and downloads
every missing uid in batches of 50. No cursor, no page, no scroll listener anywhere. A large
mailbox downloads in full on first open.

### 3.3 Sending bypasses the module entirely
`EmailRepository.sendEmail` and `EmailApi.sendEmail` exist and have **zero callers**. The real
path is: `ComposeViewModel` → `SendQueueManager` → legacy `MailingDB` row (`status = 0`) plus an
optimistic `EmailObject` (`pending = true`) → `UploadingHelper` (S3) →
`UploadingApisHandler.checkEmailExist` once every attachment is uploaded → legacy
`CommonApis.sendEmail`. A global ~10 s timer retries pending rows **with no attempt cap and no
backoff**. `Email.sendError` is modelled and persisted but never shown.

---

## 4. Functionality, screen by screen

### 4.1 List (`EmailListFragment`)
Toolbar: folder name (only `INBOX` → "Inbox"), search, filter (with a dot when active),
refresh. Action strip: select-items cycle, cancel, and a red **Empty Trash** / **Clear
&lt;Folder&gt;**. FAB "Compose". Pull-to-refresh; no swipe actions.

- **Row**: unread bar + dot, avatar (recipient in Sent/Drafts, sender elsewhere), sender
  (bold when unread; literally "Draft" in Drafts), thread count `(N)`, attachment clip,
  pending clock, time, subject (blank → "(No subject)"), 120-char snippet, overflow.
- **Dates**: same day → `h:mm a`; same year → `MMM d`; else `M/d/yy`.
- **Conversation view** groups by thread; off, it flattens and re-sorts. The toggle lives in
  settings, not here.
- **Multi-select**: long-press or the select button, capped at **30**. In conversation view
  selecting one row selects the whole thread, and moves are grouped per source folder.
  Bulk actions: delete / delete permanently (Trash) / delete drafts / move. No bulk read.
- **Row menu**: Move (not in Drafts), Delete (or "Delete Permanently" in Trash), Read By
  (Sent only).
- **Drafts** are API-only — no Realm, invisible offline, excluded from search, unmovable.

### 4.2 Folder drawer
Profile header (chevron only for Accounts-eligible users) → mailbox chooser sheet
("Personal" / "Accounts"). System folders, then an always-present "Folders" header doubling as
create, then custom folders. Custom folders get rename/delete. Per-folder unread counts come
from badge rows. Bottom nav: Mail (**no listener — dead**), Calendar, Contacts, Settings.

Folder identifiers: `INBOX`, `Sent`, `Drafts`, `Trash`, `Spam`, `Junk`. Server may return
fully-qualified names (`Vendors` → `INBOX.Vendors`), so create/rename must use the returned
name.

### 4.3 Search
**Entirely local** — a Realm query, no search endpoint; only synced folders are searchable.
500 ms debounce. Folder chips (all but Drafts), field chips (Subject/From/To on by default,
Cc/Bcc off), status, has-attachments. **Body is never searched.** Results show no date at all
and abuse `threadId` to carry the folder name.

### 4.4 Detail (`EmailDetailActivity` + `EmailTrailAdapter`)
Toolbar carries **no** email actions. Everything lives in a bottom bar: a reply-action selector
(Reply / Reply All / Forward, with Gmail-like reply-all rules), delete, and an overflow with
Move / Delete / Print (single or whole conversation).

- **Every message is always expanded** — `submitList` adds every id to `expandedIds`. The
  collapsed-preview branch and `TrailItem.isLatest` are dead.
- **Quoted text is never folded**, so a ten-message thread renders the history ten times.
- HTML bodies render in a `WebView` (JS off); plain text uses `Linkify`. `cid:` images are
  rewritten to S3 URLs with a **hard-coded bucket fallback**. No remote-image blocking.
- Attachments: non-inline only, downloaded as **base64 over the API** into the public
  Downloads folder, keyed by filename alone (so same-named files across emails collide).
  No caching, no progress percentage.
- Per-message menu adds Read By (own mail), Move (not in Sent), Print, Add to Contacts.
- No star, no mark-unread, no archive, no snooze, no share.

### 4.5 Compose (`ComposeActivity`, 1417 lines + 824 VM)
To/Cc/Bcc are always present (no show-Cc toggle), each collapsing to a chip summary on blur.
**The only recipient validation is `contains("@")`** — `a@`, `@b` and `a@b` all pass.
Autocomplete merges project users → contacts → email groups, de-duplicated so a project user
shadows a contact. A group resolves to the group's own mailbox address, not to its members.

Body is a `contenteditable` WebView with a JS bridge. Toolbar: bold, italic, underline,
strikethrough, bullet/numbered list, font size ±2 clamped 8–36, text colour (8 presets), clear
formatting. **No link, alignment, indent or font-family control.**

- **Modes**: new / reply / replyAll / forward / share / edit-draft, with `Re: ` / `Fwd: `
  prefixes added only when not already present. Forward leaves To empty but keeps any BCC
  presets. Reply-all excludes the current user by string match (aliases not handled).
- **Signature**: one-shot injection, chosen `useForNew` → the only one → hardcoded
  "Sent from Android". It lives *inside* the editable body, so the user can edit it and it is
  captured on send.
- **Drafts** auto-save on a 1 s debounce, POST then PUT; failures are silently swallowed;
  `onCleared` does a `runBlocking` save on the main thread. Opening one draft **fetches the
  entire draft list** to find it.
- **Attachments**: 25 MB cumulative cap (inline images not counted), no per-file limit, no type
  restriction. Inline images are base64-embedded (JPEG q80, max width 1200) and re-derived from
  disk at send time to swap in `cid:`. `AttachmentStatus.UPLOADING` is never assigned, so the
  send button's `hasUploadingAttachments` guard can never fire, and FAILED renders identically
  to UPLOADED.
- Send without a subject asks "Do you want to send this email without subject?"; no recipients
  gives "Please add at least one recipient". **There is no discard action and no
  discard-draft prompt** — closing always saves.

### 4.6 Settings
Conversation view switch, then cards: Signatures, Email Groups (**admin-gated**), BCC Presets
(→ legacy screen), Email Rules, Email Forwarding (sheet), Email Setup Externally (sheet).

- **Signatures**: max one, enforced only by hiding the FAB. Rich text via a bare
  `contenteditable` WebView with **no formatting toolbar**. Create always sends
  `useForNew = true, useForReply = true`; the usage-flags endpoint is wired but never called.
- **Email groups**: members are **project users only** — no free-form address, no contacts. The
  editor rebuilds members by resolving emails back to users, so an address that no longer maps
  to a project user is silently dropped and lost on save. Name is read-only when editing but
  still sent.
- **Contacts**: server-side only (no device contacts), **no search**, no sorting. 11 fields plus
  a country/zip lookup that fills state and city.
- **Credentials**: the profile now stores `enc:v1:…` ciphertext, so the password comes from
  `imap-credentials/reveal` on explicit tap only (each reveal is audit-logged). Hiding also
  discards it; 1.2 s client throttle; `FLAG_SECURE`; cleared in `onStop`; sensitive-marked
  clipboard. No password or biometric gate.
- **Calendar** is a ~40-line wrapper hosting the main module's `CalendarFragment`. Zero
  email-specific logic.

### 4.7 Rules
List → editor → run history. Model supports 7 condition fields and 8 operators; **the editor
only ever produces three** (From, Subject, Has-the-words → `subject_or_body`) always with
`contains`, plus a has-attachment checkbox. Four action types; `mark_read` parses and
round-trips but is deliberately not offered. Extensions and max-size are modelled with no UI.

Limits: 50 rules, 20 conditions, 10 actions. Reorder is long-press drag committing the full
ordered `rule_ids` — array position **is** priority. Rules created in "advanced" mode (anything
the four rows can't express) open read-only.

Validation runs in a fixed order, first match wins, and save is always tappable — pressing it
with an incomplete draft toasts, sets an inline error, and scrolls to the field.
`RuleSenderValidator` is stricter than the forward-to field, which uses `Patterns.EMAIL_ADDRESS`.

---

## 5. Data layer

**Host**: `EMAIL_BASE_URL` per flavour (`emailapi-dev` / `-qa` / prod), static — *not* routed
through `ServiceHosts` / `use_new_url`. Conversation-view, accounts-mailbox and project-users
calls go to the main `BASE_URL` instead.

**Envelope**: `{status, message, data}`. **`status == 1` is success — the HTTP code is never
branched on**, and `message` is an i18n label key resolved through `parseApiError`.

**Header**: `WITH_PROJECT_USER_ID`, or `CALLING_WITH_PROJECT_USER_ID` when an external share
session is active (`EmailProjectContext` writes `AppHelper.tempProjectId/tempUserId` first).

### The endpoint surface

| Area | Calls |
|---|---|
| Folders | `GET/POST/PUT/DELETE imap-folders` |
| Emails | `POST imap-emails/get-folder-uids`, `POST imap-emails/get-email-index`, `POST imap-emails/get-emails`, `PUT imap-emails` (move), `DELETE imap-emails`, `DELETE imap-emails/empty-trash`, `POST imap-emails/get-attachment` |
| Send | `POST imap-send` — **dead; the legacy path is used** |
| Drafts | `POST/PUT/DELETE email-draft`, `GET email-draft/{ts}/{direction}` (always `now`/`previous`) |
| Groups | `GET/POST/PUT imap-email-group`, `DELETE imap-email-group/{id}` |
| Contacts | `GET/POST email-contact`, `PUT/DELETE email-contact/{id}` |
| Signatures | `GET email-signature`, `POST …/create`, `PUT …/update`, `PUT …/update-usage-flags` (**never called**), `DELETE …/{id}` |
| Forwarding | `GET/POST/DELETE email-forwarding-setting` |
| Rules | `GET/POST email-rules`, `GET/PUT/DELETE email-rules/{id}`, `POST email-rules/reorder`, `GET email-rules/{id}/executions` |
| Credentials | `GET imap-credentials/reveal`, `PUT imap-credentials/update` |
| Settings (main host) | `PATCH user/update-conversation-view`, `PATCH project/accounts-mail-box/conversation-view`, `PATCH project/accounts-mail-box/bcc` (**no caller**) |

**Accounts mailbox** rides as `use_project_account_mailbox=true`, a **query param on every
verb** so bodies — and therefore the body-hash signature — stay untouched. Never sent as
`=false`. Two exceptions: rule *writes* put the string `"true"` in the body, and email groups
are project-scoped and never carry it at all.

### Realm
Two objects only, in the shared `zillit.realm` (`schemaVersion 60`,
`deleteRealmIfMigrationNeeded` — **any schema change wipes all cached mail**).

- `EmailObject`, key `{projectId}_{scope}_{folderName}_{messageId}` — `to`/`cc`/`bcc` and
  attachments stored as JSON strings, `hasAttachments` as the **string** `"true"`/`"false"`,
  references comma-joined. `projectEmail` is never used; `sendError` is only ever read.
- `FolderObject`, key `{projectId}_{scope}_{folderName}`.

Every query filters on `projectId` **and** `mailboxScope` (`personal` / `shared`). Contacts,
signatures, groups, drafts, rules and forwarding are **never cached**. No TTL, no purge on
logout or project switch — rows for other projects accumulate forever.

`threadId` is **computed locally**, never read from the wire: first usable `references` entry →
`in_reply_to` → own id. The wire's `thread_id` is parsed and ignored.

### Socket
The module only listens — it never emits. `inbound:email:received` → sync INBOX + current
folder; a family of move/delete/sent/draft events → re-sync the current folder; `email:read` →
local mark-read; folder and group and signature events → refresh their screens. No rule events.
Every handler no-ops when offline.

---

## 6. Defects found (verified in source)

**Three one-line socket wiring errors, all in the same block of `BaseSocketListener` —
each fails silently:**

1. `val contactUpdated = _signatureUpdated.asSharedFlow()` — `_contactUpdated` is emitted to but
   never exposed, so **no contact event is ever received**.
2. `inbound:email:delete:trail` emits onto `_emailRead`, which the list feeds to
   `markAsReadByUid` — **a delete marks the mail read instead of removing it**.
3. `email:readby:update` emits `"ReadByUpdate"` on `_signatureUpdated` — read receipts refresh
   the signature screen.

**Others worth fixing in v2 regardless of the rewrite:**

4. `ContactListViewModel.countryId` is `lateinit` with no initialiser — typing a zip before
   picking a country code **crashes**.
5. `EditContactActivity.countryResultLauncher` has an empty `try {}` with all the work in an
   unreachable `catch`; it only appears to work because a second picker overwrites its listener.
6. `moveEmails` rewrites `EmailObject.folderName` but leaves `uniqueId` (which embeds the old
   folder), so the next sync inserts a duplicate row.
7. The mailbox count pill matches the mailbox email against `level_1`, while everything else
   treats `level_1` as the uid and `level_3` as the mailbox — one of the two counts nothing.
8. Three `PATCH` calls pass an already-serialised JSON string that gets re-encoded, so the
   `bodyhash` is computed over different bytes than are sent. They work only because the
   backend does not verify it on those routes.
9. `MailCredentialsRevealer.inFlight` is reset in the callbacks rather than a `finally` — a
   callback that never fires wedges reveal for the process lifetime.
10. Failure badges on the rules list cost **one request per rule**, capped at 20 with 4-way
    concurrency; beyond 20 rules, rows show no badge and absent ≠ zero.

---

## 7. What not to carry over

- **All-expanded trail with unfolded quotes** (§3.1) — the single worst thing to copy.
- **No pagination** (§3.2) — v3 needs real paging before anything else.
- **`contains("@")` as email validation.**
- **Toolbar titles, dialog buttons, empty states and validation copy as hardcoded English
  literals** across signatures, groups, contacts and calendar. Only General Settings and the
  contact editor consistently use `R.string`. v3's no-hardcoded-strings rule means all of this
  needs string resources — 124 keys exist, the rest must be added.
- `notifyDataSetChanged()` in the folder drawer and rules adapters.
- `runBlocking` on the main thread (AWS requirements, draft save on `onCleared`).
- `delay(500)` in `onResume` to let a `NonCancellable` save land before refetching.
- Editors that refetch the whole list in their `init` just to open one item.
- Optimistic success toasts fired before the API answers.
- Dead code: `sendEmail` (both layers), `updateSignatureUsage`, `updateAccountsBcc`,
  `getSuitableRegion`, `getConfiguration`, `markSelectedAsRead`, `toggleConversationView`,
  `setSearchQuery`, `searchEmails`, `filterEmails`, `handleInlineImages`, the commented-out
  inline-image button, `RuleDriveFolderPickerSheet.onBackPressedInternal`.

---

## 8. Open questions for the backend

1. **`stop_on_match`** — the rules list states flatly that processing stops at the first match,
   but the client creates rules with `stop_on_match: false` and offers no control. Which is it?
2. **A `failed_count` on the rules list payload** would delete the per-rule badge fan-out (§6.10).
3. **A real search endpoint** — search is currently local-only, so it cannot see unsynced mail.
4. **Server-side read state** — is the badge tree intended to stay the sole source of truth, or
   should v3 send a read flag?
