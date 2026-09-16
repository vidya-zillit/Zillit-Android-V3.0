# Tools module — v2 analysis and v3 implementation plan

## What v2 does

`bottomNav/tools/Tools.kt` is 1618 lines in one fragment. Inside it:

| Concern | Size | Shape |
|---|---|---|
| `handleClickAction` | ~520 lines | one `when` over 50 tool identifiers → `startActivity` |
| `updateBadges` | ~190 lines | hand-maps ~30 identifiers to fixed fields on one badge model |
| grouping + order | ~120 lines | fetch groups, fetch order, reconcile, apply |
| list build | ~100 lines | filter, map to adapter model, empty state |

Three more `when`s over the same identifiers live elsewhere: `getToolIcon` (65 branches) and
`getSuffixMessage` (61 branches) in `utils/UIExtentions.kt`, and the badge mapping above.

**So one tool is described in four places, none of which knows about the others.** Adding a
tool means four edits in three files, and forgetting one fails silently: a missing icon
branch renders a placeholder, a missing badge branch shows no count.

### The identifier trap

A tool's list identifier and its badge key are **different strings**:

```
Constants.CATERING_TOOL          = "catering_tool"     ← /project/tools identifier
Constants.CATERING_LABEL         = "catering_label"    ← badge tree `tool`
```

This is why v2 cannot simply group badges by tool and has ~190 lines of hand-mapping instead.
Any v3 design has to carry both keys per tool.

### Data sources

| Purpose | Endpoint |
|---|---|
| Tool list | `GET /v2/project/tools`, `/v2/project/pendingtools` for pending signers |
| Admin list (enable/disable) | `GET /v2/project/tools/admin` |
| Enable/disable | `PUT /v2/project/enable-tools` `{tools:[{identifier, enabled}]}` |
| Groups | `GET/POST/PUT/DELETE /v2/project/tools/groups` |
| Move a tool between groups | `PUT /v2/project/tools/group` `{identifier, group_identifier}` |
| Per-user section order | `GET/PUT /v2/project/tools/group/order` |

### Filtering rules, in order

1. `tool == true && home != true` — the same endpoint feeds Home units and Tools tiles.
2. drop `viewAccess == false`.
3. drop the Invoices tile for accountants, whose invoices fold into the Account Hub tile.

### The listing screen, top to bottom

1. **Missing Permissions** banner. Owned by the dashboard shell, not by Tools.
2. **Admin hint card** — "To add/remove modules, please go to Settings → Admin Settings →
   Customization of tools or Click Here." Tapping it opens the customization screen. Visible
   to admins only, and it is the only way in.
3. **Search field** with a reorder button beside it.
4. **Sections**, each a header plus one rounded card holding its rows.

**Search** filters on title across every group, keeps the grouping, and **highlights the
matched substring** in bold with a background tint. No match shows its own empty state
("No tools match your search."), which is different from the no-access one.

**Section header** is the group name in caps plus **the number of tools in it**, right
aligned. A group with nothing in it is not drawn at all.

**Row order inside a section**: badge count descending, then title alphabetically. A tool
with unread jumps to the top of its section.

**Row**: tinted icon tile, title, badge bubble when the count is above zero, an info icon
opening a per-tool explainer, and a chevron.

**Group naming** falls back in three steps: the server's `group_name`, then the built-in
label key through the dictionary, then the raw identifier. A tool with a blank group goes
into an "Ungrouped" bucket.

**Section order** is the user's saved order first, then any remaining groups in the project's
default order, then any group a tool references that neither list mentioned.

### Realtime

Events: `project:tools:update`, `project:tool:group:order:update`, `tool:group:create`,
`tool:group:update`, `tool:group:delete`. The access sockets (`access:view`, `access:post`,
`access:download`) also force a tools re-fetch for two specific tools.

v2 additionally re-fetches groups, order and tools in `onResume`, because it tears its socket
listeners down when the tab is backgrounded. v3 keeps one socket for the process, so this
belt-and-braces refresh is not needed.

---

## What v3 already has

- `ProjectToolEntity` + `ProjectTool` + `ProjectDirectory.observeTools/refreshTools`, already
  fetched during project bootstrap and stored in Realm.
- `BadgeAxis.TOOL` on `BadgeManager`, so `observeGrouped(section(projectId, TOOLS), TOOL)`
  returns tool key → count in one call.
- `BadgeSection.TOOLS = "tools_label"`.
- `DashboardTab.TOOLS` rendering a placeholder.
- The label dictionary for group and tool display names.

**Missing on the entity**: `group_identifier`, `tool`, `home`, `sub_units`. These are on the
wire already; the DTO drops them. Adding them is a Realm schema bump.

---

## Plan

### Phase 1 — the tool registry (do this first)

Everything else depends on it.

```
feature/tools/
  registry/
    ToolSpec.kt          // one data class
    ToolRegistry.kt      // identifier → ToolSpec, plus badge-key → identifier
    specs/               // one file per tool, ~50 files
      CateringTool.kt
      DriveTool.kt
      …
```

`ToolSpec` carries everything v2 spreads across four files:

```kotlin
data class ToolSpec(
    val identifier: String,            // "catering_tool" — the list key
    val badgeTools: List<String>,      // ["catering_label"] — the badge keys, usually one
    val icon: ImageVector,
    val infoText: Int,                 // the (i) popup, a string resource
    val destination: (ProjectTool) -> Route?,   // null = not on Android yet
    val gate: ToolGate = ToolGate.None,         // admin-only, web-only, confirm-first
)
```

Why one file per tool rather than one big table: a tool is owned by the team building it, and
a 50-entry table is a merge conflict on every branch. The registry is the only index.

`destination` returns a typed nav `Route`, so the tile does not construct Intents and the
Tools screen has no import of any tool's UI. A tool not yet ported returns null and the
registry renders it as a "not available on mobile" tile rather than a dead tap.

**Ordering note**: build the registry with `destination = null` for every tool first, so the
list ships before any tool does. Fill destinations in as each tool is ported.

### Phase 2 — listing

1. Extend `ProjectToolDto` and `ProjectToolEntity` with `groupIdentifier`, `isTool`, `isHome`,
   `hasSubUnits`. Bump the Realm schema.
2. `feature/tools/data/ToolsRepository` — Realm-first, exactly like email. `observeTools`
   filtered by the three rules above, grouped by `groupIdentifier`.
3. `feature/tools/data/ToolGroupsApi` + `ToolGroupsRepository` — the groups list and the
   per-user order, both cached in Realm so the sections render before the network answers.
4. `ToolsViewModel` combining tools, groups, order and badges into one grouped list state.
5. `ToolsScreen` — search field, reorder button, and sections as header plus card. The row is
   a new shared component in `core`, since Home units want the same shape. Search, the
   substring highlight, the two empty states and the badge-first ordering all belong here.

### Phase 3 — badges

One call replaces v2's 190 lines:

```kotlin
badges.observeGrouped(BadgeKey.section(projectId, BadgeSection.TOOLS), BadgeAxis.TOOL)
```

The registry resolves badge key → identifier. A spec with several `badgeTools` sums them,
which covers v2's real special cases:

- Script = `script_distribution_full_label` + `script_distribution_pages_label`
- Schedule = full + pages + one-line
- Purchase Order = PO buckets + the non-accountant invoice buckets

The two genuine exceptions stay as explicit rules on the spec, not as code in the screen:

- **Invoices** is hidden for accountants, because its count is already inside Account Hub.
- **Account Hub** reads a recomputed total rather than a raw sum.

### Phase 4 — realtime

`feature/tools/data/ToolsRealtime`, modelled on `EmailRealtime`:

| Signal | Events | Action |
|---|---|---|
| `toolsChanged` | `project:tools:update`, `access:view/post/download` | `refreshTools` |
| `groupsChanged` | `tool:group:create/update/delete` | refresh groups |
| `orderChanged` | `project:tool:group:order:update` | refresh order |

Debounced, since an admin editing several tools emits a burst. No `onResume` refetch: v3 holds
its socket for the life of the process.

### Phase 5 — customization

Three screens, all reachable from the Tools tab, two of them admin-gated.

1. **Customization of tools** (admin). A flat alphabetical list with a **tick** per row, not a
   switch, and a single Update button at the bottom. Nothing is saved until Update is
   pressed. Two rules matter:
   - Turning a tool **off** that was on when the screen loaded raises a confirmation first:
     "Disabling the tool will deactivate all associated communication and data in it. Do you
     still want to proceed?" Turning one on asks nothing.
   - This list comes from `/project/tools/admin` and is **more granular than the Tools tab**.
     Budget appears as Budget (Department), Budget (Full) and Budget Builder. So the rows are
     not the same set as the tiles, and the registry has to key on identifier, not on tile.

2. **Manage tool groups** (admin) — create, rename and delete custom groups; move a tool into
   a group. v2's version is a chip row plus a tool list with a "Move" popup.

3. **Reorder groups** (every user) — a bottom sheet titled "Reorder groups", with Cancel and
   Save in its header and the line "Drag to arrange the order of the groups on your Tools
   page. This is saved only for you." Drag handles on both edges of each row. Reuse
   `ReorderableList` from `core`. The `PUT` body must contain exactly the current group
   identifiers, each once, or the backend rejects it, so reconcile the local order against
   the live group list before saving. Reflect the new order immediately and roll back if the
   save fails.

---

## Sequencing

Phase 1 and 2 together give a working Tools tab with no tool openable, which is already better
than the current placeholder. Phase 3 and 4 are small once 2 lands. Phase 5 is independent and
can slip.

Each tool's own screen is its own piece of work and is not in this plan. The registry is what
makes them independent: a tool team adds one file and one route, and touches nothing else.
