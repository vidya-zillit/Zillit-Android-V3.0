package com.zillit.zillitapp.core.socket

/**
 * Server event names.
 *
 * These are a **server contract** — the strings must match the backend exactly, and they
 * match v2's. Centralised here rather than scattered as private constants across feature
 * files (v2's shape), so a rename is one edit and a typo is caught by the compiler at the
 * call site rather than silently never firing.
 */
object SocketEvents {

    // ── Notifications / badges ────────────────────────────────────────────
    /** A new notification for this user. */
    const val NOTIFICATION_SAVE = "notification:save"

    /** Data-only: update state and badges, post nothing to the tray. */
    const val NOTIFICATION_SILENT = "notification:silent"

    /** Client → server: replay anything not delivered while we were away. */
    const val NOTIFICATION_MISSING = "notification:missing"

    /** Client → server: mark a scope read, so other devices clear too. */
    const val NOTIFICATION_READ = "notification:read"
    const val NOTIFICATION_READ_BY_IDS = "notification:readbyids"
    const val NOTIFICATION_READ_LEVEL = "notification:level:read"
    const val NOTIFICATION_ACKNOWLEDGE = "notification:acknowledge"

    /** Client → server: delete one notification. */
    const val NOTIFICATION_DELETE = "notification:delete"

    /** Client → server: clear the whole project's list (`segment = global_label`). */
    const val NOTIFICATION_DELETE_GLOBAL = "notification:delete:global"

    /** Server → client: badges cleared elsewhere. */
    const val BADGES_CLEARED_USER = "badges:cleared:user"
    const val BADGES_CLEARED_DEVICE = "badges:cleared:device"

    /**
     * Server → client: another of this user's devices read or deleted notifications.
     *
     * The echo half of [NOTIFICATION_READ] / [NOTIFICATION_DELETE] — the server tells every
     * *other* session what happened. Without these, reading your notifications on the web
     * leaves the phone's badge sitting there until the next catch-up sync.
     */
    const val NOTIFICATION_READ_SYNC = "notification:read:sync"
    const val NOTIFICATION_DELETE_SYNC = "notification:delete:sync"
    const val NOTIFICATION_DELETE_GLOBAL_SYNC = "notification:delete:global:sync"

    // ── Chat ──────────────────────────────────────────────────────────────
    const val USER_JOIN = "user:join"

    /*
     * Calling, not yet built in v3. When it lands, the token-auth spec (Aug 2026) requires
     * `user_id` in the payload of `call:end`, `call:join` and `call:migrated`, and both
     * `user_id` and `projectId` on `call:get-active-group-calls` — a token socket
     * identifies the device, so the acting user must ride in the payload. v2's
     * getActiveCallList emit omitted it and has been silently receiving
     * `unauthorized_user`; do not copy that.
     */
    const val PRIVATE_CHAT = "private_chat"
    const val GROUP_CHAT = "group_chat"
    const val CHAT_ROOM_CREATE = "chat-room:create"
    const val CHAT_ROOM_UPDATED = "chat-room:updated"

    // ── Presence ──────────────────────────────────────────────────────────
    /**
     * This device is in the foreground, or has left it. Payload `{ project_id, user_id }`.
     *
     * These only tell the *server*; nobody listens to them. The backend mirrors them into
     * Firebase Realtime Database, which is where every client reads presence from. Both
     * halves are in `PresenceRepository` so the pair cannot drift apart.
     */
    const val MARK_ONLINE = "mark_online"
    const val MARK_OFFLINE = "mark_offline"

    // ── Calendar ──────────────────────────────────────────────────────────
    /**
     * An event was created, changed or removed by someone on this project.
     *
     * All three carry a top-level `event_id`, which is what makes a targeted per-event
     * fetch possible — see `CalendarRealtime`.
     */
    /*
     * The project directory: who is on the project, what they look like, what they may do.
     *
     * These name the user they are *about* in `user_id`, so a listener must gate on the
     * project alone — the default user gate would drop every event about somebody else,
     * which is most of them.
     */
    const val PROJECT_USER_PROFILE_UPDATE = "project:user:profile:update"
    const val PROJECT_USER_PROFILE_CREATED = "project:user:profile:created"
    const val PROJECT_USER_ACCEPTED = "project:user:accepted"
    const val PROJECT_USER_REMOVED = "project:user:removed"
    const val PROJECT_USER_REORDERED = "project:user:reordered"

    /** Admin rights granted or withdrawn — changes what the user can open, not just see. */
    const val PROJECT_USER_ADMIN_ACCESS = "project:user:admin:access"

    const val DEPARTMENT_CREATE = "department:create"
    const val DEPARTMENT_UPDATE = "department:update"
    const val DEPARTMENT_DELETE = "department:delete"
    const val DEPARTMENT_REORDERED = "department:reordered"

    const val CALENDAR_EVENT_CREATE = "create:event"
    const val CALENDAR_EVENT_EDIT = "edit:event"
    const val CALENDAR_EVENT_DELETE = "delete:event"

    // ── Email ────────────────────────────────────────────────────────────────
    // The mail service fans out one event per thing that changed rather than a single
    // "mailbox changed", so the module listens to twenty of them and collapses most into
    // "resync the folder you are looking at".

    /** New mail. The only event that means "there is something you have not seen". */
    const val EMAIL_RECEIVED = "inbound:email:received"

    const val EMAIL_INBOUND_DELETED = "inbound:email:delete"
    const val EMAIL_OUTBOUND_DELETED = "outbound:email:delete"
    const val EMAIL_SENT = "outbound:email:sent"
    const val EMAIL_MOVE = "email:move"
    const val EMAILS_MOVED = "emails:moved"
    const val EMAIL_TRASH_EMPTIED = "email:trash:empty"

    /** Another device (or another person on a shared mailbox) opened a message. */
    const val EMAIL_READ = "email:read"

    /**
     * A message was removed from a conversation.
     *
     * v2 routes this onto its `email:read` channel, so a deletion marks the mail **read**
     * instead of removing it.
     */
    const val EMAIL_TRAIL_DELETED = "inbound:email:delete:trail"

    const val EMAIL_DRAFT_SAVED = "email:draft:saved"
    const val EMAIL_DRAFT_UPDATED = "email:draft:updated"
    const val EMAIL_DRAFT_DELETED = "email:draft:deleted"

    const val EMAIL_FOLDER_SAVED = "email:folder:saved"
    const val EMAIL_FOLDER_UPDATED = "email:folder:updated"
    const val EMAIL_FOLDER_DELETED = "email:folder:deleted"

    const val EMAIL_GROUP_SAVED = "email:group:saved"
    const val EMAIL_GROUP_UPDATED = "email:group:updated"
    const val EMAIL_GROUP_DELETED = "email:group:deleted"

    const val EMAIL_SIGNATURE_CREATED = "email:signature:created"
    const val EMAIL_SIGNATURE_UPDATED = "email:signature:updated"
    const val EMAIL_SIGNATURE_DELETED = "email:signature:deleted"

    /** v2 emits this onto its *signature* channel, so read receipts refresh signatures. */
    const val EMAIL_READ_BY_UPDATE = "email:readby:update"

    const val EMAIL_CONTACT_SAVED = "email:contact:saved"
    const val EMAIL_CONTACT_UPDATED = "email:contact:updated"
    const val EMAIL_CONTACT_DELETED = "email:contact:deleted"

    // ── Tools ────────────────────────────────────────────────────────────────

    /**
     * The project's tool list changed: enabled, disabled, or moved between groups.
     *
     * Also emitted after an access change, because gaining or losing view access on a tool
     * adds or removes its tile.
     */
    const val PROJECT_TOOLS_UPDATE = "project:tools:update"

    /** This user reordered their Tools sections on another device. */
    const val TOOL_GROUP_ORDER_UPDATE = "project:tool:group:order:update"

    const val TOOL_GROUP_CREATE = "tool:group:create"
    const val TOOL_GROUP_UPDATE = "tool:group:update"
    const val TOOL_GROUP_DELETE = "tool:group:delete"

    /** Viewing, posting and download rights. A lost view right removes the tile. */
    const val ACCESS_VIEW = "access:view"
    const val ACCESS_POST = "access:post"
    const val ACCESS_DOWNLOAD = "access:download"
}
