# Email module — v2 file coverage in v3

Every one of v2's **77 Kotlin files** (16,124 lines) in `bottomNav/new_email`, and where its
behaviour lives in v3. v3's own email module is 55 files / 15,459 lines, plus 15 shared files
it contributed to `core/`.

The file counts differ because v3 has no adapters (Compose has no `RecyclerView`), no
per-screen Activity/Fragment pair, and no hand-written DI modules for things Hilt can
construct — while several v2 files split into a screen plus a stateless composable.

## Structural map

| v2 file | v3 home | Note |
|---|---|---|
| `EmailConstants.kt` | `domain/EmailModels.kt` (`EmailFolders`), `navigation/EmailRoutes.kt` (compose modes) | Intent-extra keys have no analogue — routes are typed |
| `EmailLinkify.kt` | `core/ui/html/HtmlBodyView.kt` | *see gap list* |
| `EmailMailboxContext.kt` | `data/EmailMailboxContext.kt` | |
| `EmailNetwork.kt` | `core/network/NetworkMonitor` + `core/ui/ApiErrorText` | *see gap list* |
| `EmailProjectContext.kt` | — | **Not ported** — see gap list |
| `EmailReadState.kt` | `data/EmailBadges.kt` | Rewritten; v2's uid/mailbox mapping is inverted — see gap list |
| `NewEmailLandingPage.kt` | `ui/EmailLandingScreen.kt` | Nested nav graph replaced by state |
| `data/remote/api/EmailApi.kt` | `data/EmailApi.kt` | |
| `data/remote/dto/EmailDto.kt` | `data/EmailDto.kt` | |
| `data/remote/dto/EmailRuleDto.kt` | `data/EmailRuleDto.kt` | |
| `data/remote/dto/RequestDto.kt` | `data/EmailDto.kt` | Merged |
| `data/remote/dto/ResponseDto.kt` | `data/EmailDto.kt` (`EmailEnvelope`) | Merged |
| `data/remote/dto/RevealedCredentialsDto.kt` | `data/EmailDto.kt` | Merged |
| `data/remote/upload/AwsUploadManager.kt` | `core/storage/S3Client` + compose VM | Reuses the app's uploader |
| `data/repository/ContactRepositoryImpl.kt` | `data/EmailSettingsRepository.kt` | Merged |
| `data/repository/EmailRepositoryImpl.kt` | `data/EmailRepository.kt` | |
| `data/repository/EmailRuleRepositoryImpl.kt` | `data/EmailRuleRepository.kt` | |
| `data/repository/FolderRepositoryImpl.kt` | `data/EmailRepository.kt` | Merged — same cache, same scope |
| `data/repository/SendQueueManager.kt` | `ui/compose/EmailComposeViewModel.kt` | **Different design** — see gap list |
| `di/DatabaseModule.kt` | `core/database/RealmProvider` | One Realm for the app |
| `di/NetworkModule.kt` | `core/network/ZillitApi` | One client for the app |
| `di/RepositoryModule.kt` | — | Not needed: v3's repositories are concrete `@Singleton`s |
| `domain/model/Attachment.kt` | `domain/EmailModels.kt` | |
| `domain/model/Contact.kt` | `domain/EmailModels.kt` | |
| `domain/model/ConversationViewPreference.kt` | `core/session/CurrentUserStore` + settings VM | *see gap list* |
| `domain/model/Email.kt` | `domain/EmailModels.kt` | Incl. `calculateThreadId` |
| `domain/model/EmailGroup.kt` | `domain/EmailModels.kt` | |
| `domain/model/EmailRule.kt` | `domain/EmailRuleModels.kt` | |
| `domain/model/EmailSignature.kt` | `domain/EmailModels.kt` | |
| `domain/model/Folder.kt` | `domain/EmailModels.kt` | |
| `domain/repository/*.kt` (4 interfaces) | — | Not needed: one implementation each, no test doubles yet |
| `ui/calendar/EmailCalendarActivity.kt` | `ui/calendar/EmailCalendarScreen.kt` | |
| `ui/components/EmailCardAdapter.kt` | `ui/list/EmailRow.kt` + `EmailListState.kt` | |
| `ui/components/EmailTrailAdapter.kt` | `ui/detail/EmailTrailItem.kt` | |
| `ui/contacts/ContactListActivity.kt` | `ui/contacts/ContactListScreen.kt` | |
| `ui/contacts/ContactListViewModel.kt` | `ui/contacts/EmailContactsViewModel.kt` | |
| `ui/contacts/EditContactActivity.kt` | `ui/contacts/EditContactScreen.kt` | |
| `ui/email/ComposeActivity.kt` | `ui/compose/ComposeScreen.kt` + `RecipientField.kt` | |
| `ui/email/ComposeViewModel.kt` | `ui/compose/EmailComposeViewModel.kt` | |
| `ui/email/EmailDetailActivity.kt` | `ui/detail/EmailDetailScreen.kt` | |
| `ui/email/EmailDetailViewModel.kt` | `ui/detail/EmailDetailViewModel.kt` | |
| `ui/email/EmailListFragment.kt` | `ui/list/EmailListScreen.kt` + `ui/EmailRoute.kt` | |
| `ui/email/EmailListViewModel.kt` | `ui/list/EmailListViewModel.kt` | |
| `ui/email/FilterBottomSheetFragment.kt` | `ui/list/EmailFilterSheet.kt` | |
| `ui/email/FolderSelectionActivity.kt` | `ui/folder/FolderPickerScreen.kt` | |
| `ui/email/FolderSelectionViewModel.kt` | `ui/list/EmailListViewModel.kt` | Merged — same folder list |
| `ui/email/SearchActivity.kt` | `ui/search/EmailSearchScreen.kt` | |
| `ui/email/SearchViewModel.kt` | `ui/search/EmailSearchViewModel.kt` | |
| `ui/folders/FolderDrawerAdapter.kt` | `ui/drawer/FolderDrawer.kt` | |
| `ui/folders/FolderDrawerFragment.kt` | `ui/drawer/FolderDrawer.kt` + `ChooseMailboxSheet.kt` | |
| `ui/folders/FolderDrawerViewModel.kt` | `ui/list/EmailListViewModel.kt` | Merged — one screen, one model |
| `ui/rules/ActionRowAdapter.kt` | `ui/rules/RuleActionCard.kt` | |
| `ui/rules/EditEmailRuleActivity.kt` | `ui/rules/EditEmailRuleScreen.kt` | |
| `ui/rules/EditEmailRuleViewModel.kt` | `ui/rules/EmailRulesViewModel.kt` | Merged |
| `ui/rules/EmailRulesActivity.kt` | `ui/rules/EmailRulesScreen.kt` | |
| `ui/rules/EmailRulesAdapter.kt` | `ui/rules/EmailRulesScreen.kt` | |
| `ui/rules/EmailRulesViewModel.kt` | `ui/rules/EmailRulesViewModel.kt` | |
| `ui/rules/RuleDriveFolderAdapter.kt` | `ui/rules/RuleDriveFolderPickerSheet.kt` | |
| `ui/rules/RuleDriveFolderPickerSheet.kt` | `ui/rules/RuleDriveFolderPickerSheet.kt` + `data/DriveFolderApi.kt` | |
| `ui/rules/RuleExecutionsActivity.kt` | `ui/rules/RuleExecutionsScreen.kt` | |
| `ui/rules/RuleExecutionsAdapter.kt` | `ui/rules/RuleExecutionsScreen.kt` | |
| `ui/rules/RuleExecutionsViewModel.kt` | `ui/rules/EmailRulesViewModel.kt` | Merged |
| `ui/rules/RuleSenderValidator.kt` | `ui/rules/RuleValidation.kt` (`isValidSender`) | |
| `ui/rules/RuleSummary.kt` | `ui/rules/RuleSummary.kt` | |
| `ui/settings/EditEmailGroupActivity.kt` | `ui/settings/EmailGroupScreens.kt` | |
| `ui/settings/EditSignatureActivity.kt` | `ui/settings/SignatureScreens.kt` | |
| `ui/settings/EmailGroupsActivity.kt` | `ui/settings/EmailGroupScreens.kt` | |
| `ui/settings/EmailGroupsViewModel.kt` | `ui/settings/EmailSettingsViewModel.kt` | Merged |
| `ui/settings/GeneralSettingsActivity.kt` | `ui/settings/EmailSettingsScreen.kt` | |
| `ui/settings/MailCredentialsRevealer.kt` | `data/EmailSettingsRepository.kt` | |
| `ui/settings/RevealablePasswordBinder.kt` | `ui/settings/MailCredentialsSheet.kt` | |
| `ui/settings/SettingsViewModel.kt` | `ui/settings/EmailSettingsViewModel.kt` | |
| `ui/settings/SignatureListActivity.kt` | `ui/settings/SignatureScreens.kt` | |
| `ui/settings/SignatureViewModel.kt` | `ui/settings/EmailSettingsViewModel.kt` | Merged |

**Also in v3, not in v2's module** — the legacy BCC-presets screen v2 delegates to its old
mailing module (`ui/settings/BccPresetsScreen.kt`), and the change-password dialog
(`ui/settings/ChangePasswordDialog.kt`), which v2 builds inline from a shared dialog helper.

Every v2 file therefore has a home. What remains is **behavioural** parity, which is tracked
in the gap list below rather than by file.
