# Email v2 → v3: verification and gap closure

All 77 v2 `new_email` files were compared against the v3 `feature/email` module, in four
passes: compose/draft/send, list/detail/folder/badges, settings/rules/contacts/search, and
everything else (constants, linkify, network, DI, domain models, adapters).

What follows is what was **missing or wrong** and has now been fixed, then what is
deliberately still open.

---

## Fixed — data loss and broken features

| # | Problem | Fix |
|---|---|---|
| 1 | **Move to folder did nothing, anywhere.** The picker is its own nav destination, so `hiltViewModel()` built a *second* `EmailListViewModel` with an empty selection. Every move silently no-opped, and each picker open also kicked off a folder sync and a full inbox refresh. | The picker owns a `FolderPickerViewModel` that only reads folders, and reports the chosen name back through the back-stack entry. The caller does the move. |
| 2 | **"Clear folder" permanently deleted.** v2 moves to Trash. v3 called the hard-delete endpoint, so Clear Inbox was unrecoverable. | Trash empties, Drafts uses the draft endpoint, everything else moves to Trash. |
| 3 | **Per-message Delete deleted the whole conversation.** Deleting one reply in a ten-message thread destroyed all ten. | `delete`/`move` take explicit ids; the trail closes only when the last message goes. |
| 4 | **No confirmation on any destructive email action.** v2 confirms all of them. | v2's dialog and copy on row delete, bulk delete, trail delete, per-message delete, and on signature/group/contact/rule delete. |
| 5 | **A send lost to no signal was lost completely.** The composer called the send endpoint directly; backgrounding or losing signal mid-request lost the mail with no error and nothing in Sent. | New outbox (`PendingEmailEntity` + `EmailSendQueue`). The composer enqueues and closes; delivery retries on reconnect and at project open. Queued mail shows in Sent with a clock and can be cancelled. |
| 6 | **Forwarding dropped the original's attachments.** | A forward carries them, registered as already-uploaded so nothing is re-transferred. |
| 7 | **Replying from Sent addressed the mail to yourself.** | A reply to your own mail goes to its original recipients; reply-all keeps the original Cc. |
| 8 | **No images could be placed in a message body.** `ingrained_attachment` was parsed but never populated. | Image button in the shared toolbar, downscale to 1200px + recompress, `data:` preview while writing, rewritten to `cid:` at send. Draft round-trip keeps them out of the file-chip list. |

## Fixed — silent failures

Every email screen populated an `error` that nothing ever displayed: failed moves, deletes,
folder operations, signature/group/contact/rule saves, conversation-view toggles, rule
reorders. All of it was invisible.

A single `MessageBar` in `core/ui/components` now carries these, plus v2's success messages
("Email has been moved to Trash successfully." and the rest). It is an overlay, not a
`Scaffold` slot, so it works on every screen without threading a host through each one.

Also fixed: reveal-throttle and forwarding errors that displayed raw label keys; rule-run
history that read `save_attachments_to_drive:` instead of "Save attachments to Drive:"; an
unrecognised rule-failure key shown verbatim.

## Fixed — mailbox and badge scoping

- Folder counts and per-message unread were **not scoped to the open mailbox**, so a project
  with a shared Accounts mailbox mixed both mailboxes' unread into one set of counts.
- Conversation unread counted the inbox only, so a thread with an unread reply filed away by
  a rule read as fully read. Same bug in search, where a user-made folder reported everything
  as read and the Unread filter returned nothing.
- The rules screen switched the **module-wide** mailbox and never switched it back, so
  browsing Accounts rules left the inbox, composer and credentials on the shared mailbox.
- An entitlement revoked mid-session stranded the user on an empty shared mailbox. Both the
  list and the rules screen now fall back to personal.

## Fixed — settings, rules, contacts

- **Conversation view** was two unconnected flows: the switch changed itself and nothing
  else. One shared `ConversationViewPreference`, per mailbox, persisted locally, default on.
- **Credentials sheet**: the clipboard icon was wired to the same handler as the eye, so it
  revealed and never copied. SMTP/IMAP host and port were blank until reveal. A 200 with a
  blank password was shown as a successful reveal. Reveal had no in-flight guard.
- **Groups** could be saved with zero members; opening the member picker dropped anyone who
  had left the project.
- **Contacts**: a contact with only an address reached the server nameless; the postcode
  field did nothing with no country picked and never said why; "Add to contacts" was offered
  for people already saved, and only ever for the first outsider on a mail.
- **Rules**: a drag ending where it started still POSTed a reorder; toggling a switch to the
  value it already had sent a request; a rule pointing at a deleted folder showed no warning;
  the Create button at the 50-rule cap looked disabled but still worked; the action cap
  refused silently; sender suggestions were loaded and never rendered; run history paged
  twice on first load; system folders v2 excludes were offered as rule destinations;
  "Create new folder…" in place was missing.

## Fixed — smaller, user-visible

Search results are now a live query, so mail arriving while they are on screen appears in
them. Linkification (bare URLs and addresses were dead text in both HTML and plain-text bodies),
per-message Print, the sender contact sheet, Select All and the 30-row selection dialog,
the stale drawer highlight, the empty-folder flash while a folder was still syncing,
pull-to-refresh on an empty folder, the offline banner, default folders before the first
sync, alphabetical custom folders, conversation counts that included trashed messages,
reading a message offline from cache, reply-all for group addresses, recipient names that
pushed the "+ 2" off screen, recipient addresses hidden behind display names, attachment
sizes in the wrong base, attachment retry, cached files leaking on remove, a download button
labelled "Print", BCC presets, and a prefill that could overwrite text the user had typed.

---

## Deliberately still open

**Cross-project compose.** v2's `EmailProjectContext` lets a share from another project
compose and send in *that* project, using the `CALLING_WITH_PROJECT_USER_ID` header variant.
v3 declares `MODE_SHARE` but nothing can launch it: the cross-project share picker does not
exist in v3 yet. The core plumbing is present. Building the email half now would be writing
a flow with no entry point, so it waits for the picker.

**Box storage for email attachments.** v2 routes uploads to Box on a Box-storage project;
v3's email path is S3 only. Same shape as the share gap: it needs the Box transfer path,
which is not in v3 yet.

**In-app signing links.** v2 intercepts Z-E-Signature links so they open in the app. The
signing module has not been ported, so these open in a browser.

