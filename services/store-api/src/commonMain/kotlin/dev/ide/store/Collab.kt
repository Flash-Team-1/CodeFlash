package dev.ide.store

/** A file in a shared project. A null [content] in a push means the file is deleted. */
data class CollabFile(val path: String, val content: String?)

/** The head of a shared project and every file in it. */
data class CollabSnapshot(val headCommit: String?, val files: List<CollabFile>)

/** A shared project the signed-in account is a member of. */
data class CollabProject(
    val id: String,
    val name: String,
    val headCommit: String?,
    val isOwner: Boolean,
    val members: Int,
)

/** A pending invite to join a shared project. */
data class CollabInvite(
    val id: String,
    val projectId: String,
    val projectName: String,
    val fromName: String,
    val fromHandle: String?,
    val createdAt: String,
)

/** One file a commit touched, and how: added, modified or deleted. */
data class CollabChange(val path: String, val change: String)

/** One commit in a project's history, newest first. */
data class CollabCommit(
    val id: String,
    val message: String,
    val createdAt: String,
    val authorName: String,
    val authorHandle: String?,
    val changes: List<CollabChange>,
)

/** Before and after text for one file in a commit. Null on the side where the file does not exist. */
data class CollabDiff(val path: String, val change: String, val before: String?, val after: String?)
