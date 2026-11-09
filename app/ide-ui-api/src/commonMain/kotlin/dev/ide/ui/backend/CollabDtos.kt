package dev.ide.ui.backend

/** A shared project the signed-in account belongs to. */
data class UiCollabProject(val id: String, val name: String, val headCommit: String?, val isOwner: Boolean, val members: Int)

/** An invite waiting for the signed-in account. */
data class UiCollabInvite(
    val id: String,
    val projectId: String,
    val projectName: String,
    val fromName: String,
    val fromHandle: String?,
    val createdAt: String,
)

/** A file in a shared project. A null [content] in a push means delete it. */
data class UiCollabFile(val path: String, val content: String?)

/** The head commit of a shared project and all of its files. */
data class UiCollabSnapshot(val headCommit: String?, val files: List<UiCollabFile>)

/** One file a commit touched: "added", "modified" or "deleted". */
data class UiCollabChange(val path: String, val change: String)

/** One commit in a project's history. */
data class UiCollabCommit(
    val id: String,
    val message: String,
    val createdAt: String,
    val authorName: String,
    val authorHandle: String?,
    val changes: List<UiCollabChange>,
)

/** Before and after text for one file in a commit. Null on the side where the file does not exist. */
data class UiCollabDiff(val path: String, val change: String, val before: String?, val after: String?)

/** The outcome of an action that creates something: the new id, or the sentence to show when it failed. */
data class UiCollabResult(val id: String?, val error: String?)

/** Someone the account can invite to a project. */
data class UiCollabCandidate(val id: String, val handle: String?, val displayName: String)

/** A project folder's sync state: the project it is linked to (null when not shared), the version it last synced, and what changed since. */
data class UiCollabStatus(val projectId: String?, val baseCommit: String?, val changes: List<UiCollabChange>)
