# Tools module — what was built

Against the five points, with what each one actually does.

## 1. Tools listing

`feature/tools/ui/list/` — sections, search, badges.

- Source is the tool list the project directory already fetches at bootstrap. Three fields
  the DTO was dropping are now stored: the group, and the `tool`/`home` flags that separate
  Tools tiles from Home units. Realm schema 22 → 23.
- Filters in v2's order: is a tool and not a Home unit, has view access and is enabled,
  then the accountant rule below.
- Sections come from the groups endpoint, their order from the per-user order endpoint, both
  cached on device so headers render with real names on the first frame.
- Section order falls back three deep: the user's saved order, then the project default,
  then any group a tool claims that neither list mentioned. A tool can never vanish because
  its section was missing from an order array.
- Header carries the group name and the count of tiles under it. Empty sections are not drawn.
- Rows sort by badge count descending, then by name. A tool with unread rises to the top of
  its section.
- Search filters across every group, keeps the grouping, and bolds the matched substring.
  It has its own empty state, separate from the no-access one.

## 2. Customization

Three screens, because they are three different things.

| Screen | Who | What it writes |
|---|---|---|
| Customization of tools | admin | `PUT /project/enable-tools` |
| Manage tool groups | admin | create, rename, delete a group; move a tool into one |
| Reorder groups | everyone | `PUT /project/tools/group/order`, per user |

The enable/disable list is the **admin** endpoint, which is a different and longer list than
the tab: it is finer grained and it includes what is switched off. Ticks are local until
Update. Turning a tool **off** that was on when the screen opened asks first, with v2's
warning about deactivating its communication and data; turning one on asks nothing.

Reorder is a bottom sheet with Cancel and Save, drag handles on both edges, and a line saying
the order is saved only for you. The order sent is reconciled against the live group list
first, because the backend requires exactly the current identifiers, each once.

## 3. One entry class per tool

`feature/tools/registry/` — `ToolSpec`, `ToolRegistry`, and **50 files under `specs/`**, one
per tool.

v2 describes a tool in four places that do not know about each other: a branch in the click
`when`, one in `getToolIcon`, one in `getSuffixMessage`, and a line in the badge mapping.
A `ToolSpec` is that description once: identifier, badge keys, icon, explainer, destination,
availability.

Two things the split makes possible:

- **The identifier mismatch is data, not code.** A tool's list key and its badge key are
  different strings, and irregularly so: `catering_tool` against `catering_label`, but
  `callsheet_tool` against `call_sheet_label` and `accounting_tool` against `accounts_label`.
  Each spec carries both, and the registry inverts the map once.
- **A tool with no screen yet is honest.** `destination = null` renders the tile dimmed and
  says where the tool can be used. All 50 are null today; filling one in is a one-line change
  in that tool's own file.

A tool the server enables that has no spec still appears, with a fallback icon.

## 4. Realtime

`feature/tools/data/ToolsRealtime` — five events on three signals.

| Signal | Events | Reload |
|---|---|---|
| tools changed | `project:tools:update`, `access:view/post/download` | the tool list |
| groups changed | `tool:group:create/update/delete` | the groups |
| order changed | `project:tool:group:order:update` | the order |

The first two are debounced, since an admin switching six tools off emits six events. There
is no refresh-on-resume: v2 needs one because it drops its listeners when the tab is
backgrounded, and v3 holds one socket for the process.

## 5. Badges

One call, where v2 has about a hundred and ninety lines:

```kotlin
badges.observeGrouped(BadgeKey.section(projectId, BadgeSection.TOOLS), BadgeAxis.TOOL)
```

The registry folds the badge-keyed counts into tool-keyed ones. A tool owning several badge
keys sums them, which is how the real special cases are expressed as data:

- Schedule counts the full schedule, its pages and the one-liner.
- Script counts the full script and its pages.
- Budget counts its own key and its chat key.

The two rules that are not sums stay as availability on the spec:

- **Invoices** is hidden from senior accountants, whose invoice counts already sit inside
  Account Hub. The rule is v2's: an admin holding one of two accounts designations.
- **Account Hub** reads its own recomputed total.

## Not done

- **No tool opens yet.** Every spec has a null destination, because no tool's own screen
  exists in v3. The tab, the sections, the search, the badges and all three customization
  screens work; tapping a tile says the tool is on the web.
- **Nothing has run on a device.** The APK builds. That is all that has been verified.

Realm schema went 22 → 23, so the tool cache rebuilds on first launch.
