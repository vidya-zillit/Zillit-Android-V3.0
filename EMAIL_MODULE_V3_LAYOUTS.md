# Email module in v3 — all 31 layouts

Every ViewBinding layout in v2's `bottomNav/new_email`, and the Compose screen that replaces
it. Built UI-first: each screen is stateless — it takes a state object and callbacks — so the
repositories and view models can be wired without touching any of it.

## The 31

| # | v2 layout | v3 composable | File |
|---|---|---|---|
| 1 | `new_email_landing_page` | `EmailLandingScreen` | `ui/EmailLandingScreen.kt` |
| 2 | `fragment_email_list` | `EmailListScreen` | `ui/list/EmailListScreen.kt` |
| 3 | `item_email_card` | `EmailRow` | `ui/list/EmailRow.kt` |
| 4 | `fragment_filter_bottom_sheet` | `EmailFilterSheet` | `ui/list/EmailFilterSheet.kt` |
| 5 | `fragment_folder_drawer` | `FolderDrawer` | `ui/drawer/FolderDrawer.kt` |
| 6 | `item_drawer_header` | `SectionHeader` | `ui/drawer/FolderDrawer.kt` |
| 7 | `item_folder` | `FolderRow` | `ui/drawer/FolderDrawer.kt` |
| 8 | `bottom_sheet_choose_mailbox` | `ChooseMailboxSheet` | `ui/drawer/ChooseMailboxSheet.kt` |
| 9 | `fragment_search` | `EmailSearchScreen` | `ui/search/EmailSearchScreen.kt` |
| 10 | `fragment_folder_selection` | `FolderPickerScreen` | `ui/folder/FolderPickerScreen.kt` |
| 11 | `item_folder_selection` | `FolderPickerRow` | `ui/folder/FolderPickerScreen.kt` |
| 12 | `activity_email_detail` | `EmailDetailScreen` | `ui/detail/EmailDetailScreen.kt` |
| 13 | `item_email_trail` | `EmailTrailItem` | `ui/detail/EmailTrailItem.kt` |
| 14 | `activity_compose` | `ComposeScreen` | `ui/compose/ComposeScreen.kt` |
| 15 | `item_recipient_chip` | `RecipientChip` | `ui/compose/RecipientField.kt` |
| 16 | `email_fragment_settings` | `EmailSettingsScreen` | `ui/settings/EmailSettingsScreen.kt` |
| 17 | `fragment_signature_list` | `SignatureListScreen` | `ui/settings/SignatureScreens.kt` |
| 18 | `item_signature` | `SignatureCard` | `ui/settings/SignatureScreens.kt` |
| 19 | `fragment_edit_signature` | `EditSignatureScreen` | `ui/settings/SignatureScreens.kt` |
| 20 | `fragment_email_groups` | `EmailGroupsScreen` | `ui/settings/EmailGroupScreens.kt` |
| 21 | `item_email_group` | `EmailGroupCard` | `ui/settings/EmailGroupScreens.kt` |
| 22 | `fragment_edit_email_group` | `EditEmailGroupScreen` | `ui/settings/EmailGroupScreens.kt` |
| 23 | `bottom_sheet_email_forwarding` | `EmailForwardingSheet` | `ui/settings/EmailForwardingSheet.kt` |
| 24 | `mail_credentials_vw` | `MailCredentialsSheet` | `ui/settings/MailCredentialsSheet.kt` |
| 25 | `fragment_contact_list` | `ContactListScreen` | `ui/contacts/ContactListScreen.kt` |
| 26 | `item_contact` | `ContactCard` | `ui/contacts/ContactListScreen.kt` |
| 27 | `fragment_edit_contact` | `EditContactScreen` | `ui/contacts/EditContactScreen.kt` |
| 28 | `fragment_contact_bottom_sheet` | `ContactActionSheet` | `ui/contacts/ContactActionSheet.kt` |
| 29 | `activity_email_rules` | `EmailRulesScreen` | `ui/rules/EmailRulesScreen.kt` |
| 30 | `item_email_rule` | `RuleCard` | `ui/rules/EmailRulesScreen.kt` |
| 31 | `activity_edit_email_rule` | `EditEmailRuleScreen` | `ui/rules/EditEmailRuleScreen.kt` |
| 32 | `item_rule_action` | `RuleActionCard` | `ui/rules/RuleActionCard.kt` |
| 33 | `activity_rule_executions` | `RuleExecutionsScreen` | `ui/rules/RuleExecutionsScreen.kt` |
| 34 | `item_rule_execution` | `ExecutionCard` | `ui/rules/RuleExecutionsScreen.kt` |
| 35 | `sheet_rule_drive_folder_picker` | `RuleDriveFolderPickerSheet` | `ui/rules/RuleDriveFolderPickerSheet.kt` |
| 36 | `item_rule_drive_folder` | `DriveFolderRow` | `ui/rules/RuleDriveFolderPickerSheet.kt` |
| 37 | `activity_email_calendar` | `EmailCalendarScreen` | `ui/calendar/EmailCalendarScreen.kt` |
| 38 | `item_attachment` | `AttachmentRow` | **shared** — `core/ui/components/` |

(31 are the ViewBinding layouts; the rest are the item layouts inflated directly.)

`fragment_email_calendar_wrapper.xml` is an orphan in v2 — no Kotlin references it. Not ported.

---

## What went into `core/` instead of the email module

The rule is one component per (same UI + same behaviour). Seven were added, and existing
duplicates across the app were migrated onto them.

| Component | Where it lives | Replaces |
|---|---|---|
| `SearchField` | `core/ui/components/SearchField.kt` | **9 hand-rolled search inputs** that had drifted into two different looks — two of them were literally private composables called `SearchField`. Now used by chat's Read By, the common list picker, four calendar screens, the project list and two email screens. |
| `SelectionBar` + `SelectionAction` | `core/ui/components/SelectionBar.kt` | Chat's single-action selection bar, generalised to a list of actions (email offers delete / delete-permanently / move) with an optional `3 / 30` cap. |
| `SettingsGroup`, `SettingsNavRow`, `SettingsSwitchRow`, `SettingsRowDivider` | `core/ui/components/SettingsRows.kt` | The private row composables inside project settings. The shared row grew `emphasis`, `showDot`, `badgeCount` and `onInfo`, so project settings is now a 20-line mapping instead of a 70-line row. |
| `ZillitFilterChip`, `FilterChipRow`, `FilterChipFlow` | `core/ui/components/FilterChips.kt` | Three near-identical filter-chip treatments in the calendar screens. |
| `AttachmentRow`, `StagedAttachmentRow`, `formatFileSize` | `core/ui/components/AttachmentRow.kt` | Nothing existed; used by the compose screen, the message trail, and any future attachment list. Adds a real `FAILED` state — v2 renders a failed upload identically to a successful one. |
| `HtmlBodyView` + `sanitizeEditableHtml` | `core/ui/html/` | The app only had `fromSimpleHtml()`, which handles `<b>` and `<br>` and discards everything else. Mail bodies are full documents, so they need a real renderer. JavaScript stays off; height is polled from `contentHeight` because the page cannot report it. |
| `RichTextEditor` + `RichTextToolbar` | `core/ui/html/` | The composer's body and the signature editor. v2 gives the signature editor a bare `contenteditable` with **no toolbar at all**, so a signature could only be styled by pasting styled text in. |
| `ReorderableListState` + `Modifier.reorderable` | `core/ui/components/ReorderableList.kt` | Compose has no `ItemTouchHelper`. The rules list needs it because a rule's array position **is** its priority. Reorders locally while dragging, commits once on release. |

`PullToRefreshBox` and `ModalBottomSheet` come from Material 3 — no wrappers written.

---

## Deliberate departures from v2

Copy is v2's, verbatim — 161 strings lifted straight out of v2's `strings.xml`, and new
resources created with v2's exact literal text wherever v2 hardcoded it (339 strings total).
Behaviour differs in seven places, all of them things the module audit flagged:

1. **The trail is not all-expanded.** v2's adapter adds every id to its expanded set inside
   `submitList`, so a ten-message thread renders ten full bodies and its own
   collapsed-preview branch is dead code. Newest opens; the rest are one-line previews.
2. **Quoted history folds.** v2 renders it inline, so a ten-message thread contains the first
   message ten times.
3. **Email validation is real.** v2's only check is `contains("@")` — `a@`, `@b` and `a@b`
   all pass and fail at the server. See `isPlausibleEmail`.
4. **Quoted HTML is sanitized before it reaches the editor.** The composer's WebView must
   have JavaScript on for the toolbar to work, and v2 injects an attacker-authored quoted
   body into it raw.
5. **The zip-code lookup cannot crash.** v2's `ContactListViewModel.countryId` is `lateinit`
   with no initialiser, and the lookup fires on the zip field blurring while checking only
   that the zip is non-empty — typing a postcode before picking a country throws.
6. **The folder picker has an empty state.** v2 shows a spinner whose only exit is a
   non-empty list, so a mailbox with nowhere to move to spins forever.
7. **Contacts can be searched.** v2 has no search and no sorting on that screen.

Everything else — field order, validation order, menu contents and conditions, the
reply-all rule, the 30-selection cap, folder naming, date formats — follows v2 exactly.
