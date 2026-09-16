# v2 → v3: what is missing

Measured from both codebases on 12 Sep 2026, not from memory. Sizes are Kotlin line counts,
which say how much *work* a module was, not how good it is.

- **v2**: ~790,000 lines, of which `bottomNav/tools` alone is 451,813
- **v3**: 78,783 lines

Line count is a poor completeness measure — v3's email is 17,381 lines against v2's 16,124
and is broadly equivalent — so everything below is judged on whether the thing **opens and
works**, not on size.

Status words used throughout:

| Word | Means |
|---|---|
| **Done** | built and working |
| **Partial** | opens, but something material is missing |
| **Shell** | reachable, renders, no real data behind it |
| **Stub** | a placeholder screen with a title and nothing else |
| **Missing** | not started |

---

## 1. The five tabs

| Tab | v2 | v3 | Status |
|---|---|---|---|
| Home | 9,103 | 2,902 + 1,776 core chat | **Partial** |
| Email | 16,124 | 17,381 | **Done** |
| Tools | 451,813 | 3,581 | **Shell** |
| C&C | 9,071 + 13,759 thread | 4,828 + data layer | **Shell** |
| Settings | 27,119 | 789 | **Partial** |

### Home — Partial
Bulletin and Call Sheet units work, including the replace flow. Missing: everything under
v2's `otherchats` (3,504 lines), and the Home unit kinds beyond those two.

### Tools — Shell
This is the single largest gap in the app. The tab lists tools, reorders them, shows badges
and info, and the customisation screens work. **But all 51 tool entries have
`destination = null`, so not one of them opens anything.**

v2's 40 tool packages, largest first:

| Tool | v2 lines | Tool | v2 lines |
|---|---|---|---|
| Account Hub | 130,475 | Wardrobe | 9,080 |
| Forms & Signature | 35,719 | Budget | 8,503 |
| DocuSign | 34,996 | Box Schedule (old) | 5,955 |
| Deal Memo | 28,153 | Map (old) | 5,579 |
| Drive | 25,482 | SA Portal | 4,953 |
| Ad Dashboard | 22,400 | Sides | 4,860 |
| Box Schedule (new) | 14,995 | Continuity | 4,617 |
| Production Report | 13,991 | Schedule | 3,935 |
| Call Sheet | 12,889 | Confidential Info | 3,786 |
| Location | 12,780 | Script | 3,640 |
| Casting | 11,283 | Weather Pro | 3,214 |
| Purchase Order | 10,989 | Accounts | 2,487 |
| Map (new) | 9,755 | Catering | 2,196 |

Plus: DOD, Zillit Drive, Distribution List, Crew List, Script Notes, Report Tool, Weather,
Scanner.

### C&C — Shell
UI is built and approved: chat/call/contacts lists, direct and group threads, profile, group
info, create group, all on real navigation destinations. The data layer is written and
compiles — storage, socket transport, repository, realtime coordinator, typing, presence,
recent ordering, encryption. **None of it is connected to a screen.** See
`CNC_DATA_LAYER_V2_AUDIT.md` §8 for the item-level list.

### Settings — Partial
Only **App Preferences** is a real screen. Linked Devices, Invite Users and Help are real but
live elsewhere. These eight are **stubs** — a title bar and nothing else:

Edit My Profile · Privacy Preferences · Recovery Code · Account Settings · Catering Settings
· Admin Settings · Leave Project · Update App

---

## 2. Whole modules missing

| Module | v2 lines | Notes |
|---|---|---|
| **Calling** | 32,322 + 27,770 | LiveKit and the v2 calling stack. Deferred by your decision; buttons exist and are inert. |
| **Transportation** | 27,421 | Not started. |
| **Document Distribution** | 21,893 | Not started. Referenced in 4 files as a destination that does not exist. |
| **Media editor** | 20,100 | v3 has image crop and annotate under `core/attachment/editor`; v2's is far larger. **Partial.** |
| **Pre-production** | 14,986 | Not started. |
| **Recce** | 3,972 | Not started. |
| **Asset Register** | 2,033 | Not started. |
| **Consent (GDPR)** | 1,448 | Not started. Has a written spec. |
| **Pending-user dashboard** | 1,396 | Not started — the screen a user sees before approval. |
| **Tooltip / onboarding** | 1,559 + 295 | Not started. |
| **Personal project** | 1,061 | Not started. |
| **SOS** | 2,139 | **Stub.** The button is in the top bar and opens a placeholder. |

---

## 3. Cross-cutting things that exist but are incomplete

- **Join Project** — a stub. Creating a project works; joining one does not.
- **Forward and Share** — the sheet exists in `core/ui/chat`; the cross-project picker and the
  posting-into-another-project path are not built.
- **Translation** — v2 has a translate action on a message; v3 stores `isTranslated` and has
  `applyTranslation`, but nothing offers it.
- **Read By User** — screen and view model exist; reachable only from email.
- **Calendar** — v3 ≈ v2's `new_calendar`. v2's legacy `calendar` (22,399 lines) is not
  carried over, which is correct if the new one has replaced it in production.

---

## 4. What v3 has that v2 does not

Worth recording so the comparison is honest in both directions.

- Light and dark themes as a token system, applied everywhere
- One chat surface instead of v2's eight near-duplicate chat screens
- A durable upload queue that survives process death, now with parallel uploads
- Typed, type-safe navigation instead of ~400 hand-built intents
- A socket event gate that enforces project and device scoping declaratively
- Realm holding decrypted text, so search matches words rather than ciphertext
- Presence read per row instead of one shared listener that only worked on one screen

---

## 5. Suggested order

1. **Connect C&C.** The data layer is written; three items turn it from compiled to working.
2. **Fill the Settings stubs.** Eight small screens, all reachable today, all dead ends.
3. **Join Project and SOS.** Both are one screen each and both are visible from day one.
4. **Pick the first real tool.** Nothing in the Tools tab opens, so the first one also builds
   the pattern every other tool follows. Confidential Info (3,786) or Accounts (2,487) are the
   smallest chat-shaped ones; Call Sheet (12,889) is already half-built through the Home unit.
5. **Then the large tools**, by what production actually needs first.
