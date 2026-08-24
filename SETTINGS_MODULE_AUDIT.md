# Settings — what v2 does, and what v3 now does

Read from v2's `bottomNav/settings/SettingPage.kt` (928 lines), `fragment_settings.xml`, and
`badgesHandler/CommonBadgesHandler.kt`.

---

## 1. Every piece of UI on v2's settings page

### Header
| Element | Behaviour |
|---|---|
| Avatar | Profile picture, or the first letter when there is none. **Tapping it opens the full-size photo**, only when a photo exists. |
| Name | `fullName` |
| Designation | `designationName`, resolved through the label dictionary |
| ✎ edit icon | Present in the layout (`imgEdit`) |

### The list (a RecyclerView, `recItems`)
Every row is title + prefix icon + **ⓘ** + chevron. Tapping ⓘ opens an info dialog titled
`"<row title>:"` with the blurb; tapping the row navigates.

| # | Title | ⓘ blurb | Shown when | Opens |
|---|---|---|---|---|
| 1 | App Preferences | Mute notifications, set default opening tabs, app lock. | always | `AppPreferencesPage` |
| 2 | Privacy preferences | Choose what Zillit may collect, or withdraw your consent… | always (GDPR — never gated on rights) | consent screen |
| 3 | Connect to Zillit from your laptop | Allows your phone to sync with your laptop or desktop… | **only when no laptop is linked** (`primaryDeviceId` empty) | `LinkedDevicePage` |
| 4 | Edit My Profile | Change name, designation, photo, etc.. See your private email i.d.… | always | `EditProfileDataActivity` |
| 5 | Invite Users | Code; to be shared with others to join the project. | always | `ShareProjectCodeActivity` (`hide_btn`) |
| 6 | Recovery Code or Email | Add/Edit your recovery email… | always | a dialog, not a page |
| 7 | Update App | Current app version is %s… | always, **with a dot when an update exists** | Play Store URL |
| 8 | Zillit Help | Shows information relating to Zillit. | always | in-app help |
| 9 | Account Setting | Create different units like General, Petty Cash, Payroll etc. | department = `department_accounts` **and** `accounting_tool` enabled | `AccAndCaterSettingsActivity` |
| 10 | Catering Settings | Create different units like Breakfast, Launch, Dinner… | department = `department_catering` **and** `catering_tool` enabled | same, different mode |

**Then `list.sortBy { it.title }`** — the list is alphabetical, which is why v2's screen reads
App Preferences → Connect… → Edit My Profile → Invite Users → Privacy… → Recovery… → Update
App → Zillit Help.

### Pinned after the sort (so they stay at the bottom)
| Title | Shown when | Colour | ⓘ | Badge |
|---|---|---|---|---|
| Admin Settings | `isAdmin` | blue | none | `settingsTotal` |
| Leave This Project | always | red | yes — **different text for admin vs member** | none |

### The ⓘ dialog — three things, not one
v2's `BaseFragment.openInfoDialog(message, header, identifier)` builds one dialog carrying:

| Part | Detail |
|---|---|
| Title | `"<row title>:"` |
| Message | the blurb, **with "More" spliced onto the end as a blue underlined link** (a `ClickableSpan`) into the documentation site |
| Left button | **Ok** — dismiss |
| Right button | **Watch Video** — opens a WebView titled "Tutorial" on a tutorial `.mp4` |

**Both links depend on who is reading**, resolved by `VideoExtension.kt`:
- `getVideoUrl(identifier)` — a `when` over ~90 keys. Several branch on the reader:
  Edit My Profile has separate admin and member recordings; the Accounts and Catering
  tutorials differ for people *in* that department; Forms & Signature differs for admins.
- `getMoreLinkUrl(identifier)` → `getEndPoint()` — the documentation host plus
  **`?for=accounts#anchor`** for the Accounts department, **`?for=admin#anchor`** for an
  admin, and plain **`#anchor`** for everyone else.

### Dialogs
- **Leave This Project** → "Are you sure you want to leave the project?" / No / Yes →
  `PUT user/leave-project`.
- **Recovery Code or Email** → its own dialog.
- **ⓘ** on any row → title + blurb.
- (Delete Project / Stop Project Deletion exist in the layout and the code but are commented
  out in this build.)

---

## 2. What v3 does differently — and what it keeps

**Kept exactly:** every row, every title, every ⓘ blurb, the alphabetical order, the pinned
Admin/Leave pair, the leave confirmation wording, and the role rules.

**Changed:** v2 draws one undivided list where Admin Settings and Leave This Project are
distinguishable only by text colour. v3 groups them into a separate card, gives the profile
header a proper card of its own, tints each row's icon by its emphasis, and keeps the ⓘ as a
real touch target rather than a 12dp glyph. Sorting is done on the **resolved** title so it
stays correct in other languages — v2 sorts the same way but on the English string.

---

## 3. The ⓘ system in v3 — built to be used everywhere

Three pieces, all in `core`, none of them settings-specific:

| File | Job |
|---|---|
| `core/help/HelpTopic.kt` | The catalogue. One entry per topic, carrying its tutorial path, an optional **admin-only** recording, and its documentation anchor. v2 spreads the same mapping across 859 lines of `when`; here a missing link is a compile error rather than a silent fall-through to a placeholder clip. |
| `core/help/HelpLinks.kt` | Resolves a topic **for the current reader** — admin recording vs member recording, `?for=accounts` vs `?for=admin` vs neither. One place, reading the directory rather than a global. |
| `core/ui/components/InfoDialog.kt` | The dialog: title, message with **More** appended as an inline link, **Watch Video** when a tutorial exists, **Ok**. Both buttons vanish on their own when a topic has no video or no page. |

Using it from any screen is three lines:

```kotlin
// in the ViewModel
fun linksFor(row: MyRow) = row.topic?.let(helpLinks::linksFor)

// in the composable
InfoDialog(
    title = stringResource(row.titleRes),
    message = stringResource(row.infoRes),
    links = viewModel.linksFor(row) ?: HelpLinksFor(),
    onDismiss = { info = null },
)
```

Links open in the browser rather than an in-app WebView, matching the choice `HelpScreen`
already made: a documentation page is worth sharing and a video is better full-screen.

**Verified on the emulator** as an admin in the Catering department:
- More → `https://documentation.zillit.com/?for=admin#edit-my-profile`
- Watch Video → `…/Android/Settings/Adminsetting/How%20to%20Edit%20My%20Profile%20admin.mp4`

Both the admin variants, which is what v2 serves the same user.

---

## 4. Badge logic

| Where | Count | Source |
|---|---|---|
| Settings **tab** (bottom bar) | everything unread under `settings_label` | v2's `settingsTotal`; "Approve (Profile and New User) requests" |
| **Admin Settings** row | the same number | v2 assigns `badgeCount = settingsTotal` to that row |
| Every other row | none | — |
| Update App | a **dot**, not a count | v2's `Constants.isAppUpdateAvailable` |

So the tab badge and the row badge are the same set of notifications: an approval waiting on
an admin. A member never sees either, because a member has no Admin Settings row — and
nothing else in Settings raises a badge. In v3 both read `BadgeKey.section(projectId,
settings_label)`, so they cannot disagree.

**Not carried over:** the update dot. Nothing in v3 checks for a newer version yet, so
nothing sets it.

---

## 5. Role and department cases

| Case | Effect |
|---|---|
| **Admin** | Admin Settings row appears (blue, with the approvals badge). Leave's ⓘ says an admin can only leave if another admin exists. |
| **Not admin** | No Admin Settings row, no badge. Leave's ⓘ is the shorter member text. |
| **Department = Accounts** *and* accounting tool on | "Account Setting" row appears |
| **Department = Accounts**, tool **off** | no row — there would be nothing to configure |
| **Department = Catering** *and* catering tool on | "Catering Settings" row appears |
| **Any other department** | neither row |
| **Department changed while the page is open** | the row appears or disappears immediately — the list is rebuilt from the directory rather than fetched once |
| **Made an admin while the page is open** | Admin Settings appears the same way |
| **Laptop already linked** | v2 hides "Connect to Zillit from your laptop" — **not yet carried over**, see gaps |
| **Admin vs member ⓘ** | different tutorial video and a different documentation page — see §3 |
| **Accounts department ⓘ** | documentation served as `?for=accounts` |

Verified on the emulator with the test account (Catering department, admin): Catering
Settings and Admin Settings both present, alphabetical order correct, ⓘ and Leave dialogs
carrying v2's exact wording.

---

## 6. What is deliberately empty

Each row opens a real page with its own title and "This page is not built yet.":
Edit My Profile · Privacy preferences · Recovery Code or Email · Invite Users · Account
Setting · Catering Settings · Admin Settings · Leave This Project.

Two are already real: **App Preferences** (the existing screen, now with a title bar) and
**Connect to Zillit from your laptop** (the existing Linked Devices screen).

## 7. Profile pictures, and keeping the page live

### Why no picture ever showed
The server sends `profile_picture` as an **object key** in the project's private bucket —
`profile/file/<project>/Zillit_….jpeg` — plus a `thumbnail` key, a bucket and a region. v3
stored `media` in a field called `profilePictureUrl` and handed it straight to Coil, which
has nothing to fetch from a bare key, so every avatar silently fell back to initials. v2
never treats it as a URL either: it downloads the object and shows the file.

Fixed by routing avatars through the downloader the chat already uses (`rememberAttachmentImage`
→ signed S3 download → disk cache). Three follow-ons:
- `profile_picture.thumbnail` is now stored as well (schema **16**) and preferred — an avatar
  is 36–56dp and does not need the original.
- `UserAvatar` in `core/ui/components` is now the app's only avatar. `ChatBubbles` had a
  private initials-only copy, which is why a crew member's photo never appeared on their
  messages either; it now calls the shared one.
- **No picture still shows the name's letters**, and so does the moment while one downloads,
  so the circle is never empty.

**Verified on the emulator:** Vidya Pixel's photo renders on their chat messages; the
signed-in account, whose `profile_picture.media` is genuinely `""`, shows "VS". Both correct.

### Realtime — `core/directory/DirectoryRealtime.kt`
The directory is loaded once when a project opens, so a profile edited elsewhere stayed stale
until a restart. It now listens to v2's own events and **refreshes the store**, not the
screen — every consumer already observes Realm, so one refresh updates the settings header,
the unit strip, every chat author and the pickers at once.

| Events | Refreshes |
|---|---|
| `project:user:profile:update` · `:created` · `project:user:accepted` · `:removed` · `:reordered` | users |
| `project:user:admin:access` | users **+ tools + units** — rights change what is on screen, not only what it says. v2 calls `getUserUnitAccess()` on the same event |
| `department:create` · `:update` · `:delete` · `:reordered` | departments **+ users**, since a user row carries its department's name |

Bursts are debounced 400ms — a department rename arrives as one event per affected user —
and events for other projects are dropped. So an admin granting rights, or someone changing
their photo or department, updates this page while it is open.

---

## 8. Gaps to close when the inside pages are built

- **Connect to Zillit** should be hidden once a laptop is linked. v2 reads a cached device
  detail; v3 would need the linked-device state cached rather than fetched, so it is left
  visible for now.
- **Update App** has no dot and no Play Store link yet — no update check exists in v3.
- **Invite Users** is a placeholder rather than a jump to the existing share-code screen: that
  screen takes the project code as a route argument and nothing in the session carries it.
- **Leave This Project** confirms correctly but the confirmation currently opens a placeholder
  instead of calling `PUT user/leave-project`.
