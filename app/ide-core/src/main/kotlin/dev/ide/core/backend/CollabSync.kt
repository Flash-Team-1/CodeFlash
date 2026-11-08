package dev.ide.core.backend

import dev.ide.ui.backend.UiCollabChange
import dev.ide.ui.backend.UiCollabFile
import dev.ide.ui.backend.UiCollabResult
import dev.ide.ui.backend.UiCollabSnapshot
import dev.ide.ui.backend.UiCollabStatus
import java.io.File
import java.nio.ByteBuffer
import java.util.Properties

/**
 * Keeps a project folder in step with its shared copy.
 *
 * The folder remembers the version it last synced, under `.codeassist/collab`. That memory is what makes
 * the sync safe: a push sends only what this device changed, and a pull never overwrites an edit that has
 * not been pushed yet. A file changed on both sides is reported and left alone.
 *
 * Only UTF-8 text files take part. Build output, local settings and keystores never leave the device.
 */
internal class CollabSync(
    private val fetch: suspend (String) -> UiCollabSnapshot?,
    private val push: suspend (String, String?, String, List<UiCollabFile>) -> String?,
    private val create: suspend (String, List<UiCollabFile>) -> UiCollabResult,
) {
    fun status(root: File): UiCollabStatus {
        val state = readState(root) ?: return UiCollabStatus(null, null, emptyList())
        val changes = diff(listLocal(root), readBase(root)).map { UiCollabChange(it.path, it.change) }
        return UiCollabStatus(state.projectId, state.baseCommit, changes)
    }

    /** Creates the project from this folder. The first commit holds every text file in it. */
    suspend fun share(root: File, name: String): UiCollabResult {
        if (readState(root) != null) return UiCollabResult(null, "This folder is already shared")
        val local = listLocal(root)
        if (local.isEmpty()) return UiCollabResult(null, "There are no text files here to share")
        val created = create(name, local.map { UiCollabFile(it.key, it.value) })
        val id = created.id ?: return created
        val head = fetch(id)?.headCommit
        writeBase(root, local)
        writeState(root, id, head)
        return UiCollabResult(id, null)
    }

    /**
     * Links this folder to a project that already exists and pulls it. Files already here that the project
     * does not have are kept and pushed later; a file that exists on both sides with different text stops the pull.
     */
    suspend fun join(root: File, projectId: String): String? {
        if (readState(root) != null) return "This folder is already shared"
        writeBase(root, emptyMap())
        writeState(root, projectId, null)
        return pull(root)
    }

    suspend fun pull(root: File): String? {
        val state = readState(root) ?: return NOT_LINKED
        val snap = fetch(state.projectId) ?: return UNREACHABLE
        return merge(root, state.projectId, snap)
    }

    suspend fun push(root: File, message: String): String? {
        val state = readState(root) ?: return NOT_LINKED
        val snap = fetch(state.projectId) ?: return UNREACHABLE
        // The shared project moved since the last sync: bring the non-clashing changes in first.
        if (snap.headCommit != state.baseCommit) merge(root, state.projectId, snap)?.let { return it }
        val local = listLocal(root)
        val changes = diff(local, readBase(root))
        if (changes.isEmpty()) return "Nothing changed since the last sync"
        val files = changes.map { UiCollabFile(it.path, if (it.change == "deleted") null else local[it.path]) }
        // The server refuses this if the head moved after the fetch above, and the user pulls again.
        push(state.projectId, snap.headCommit, message, files)?.let { return it }
        writeBase(root, local)
        writeState(root, state.projectId, snap.headCommit)
        return null
    }

    /** Applies the shared changes that do not touch a file changed here. Nothing is written when any file clashes. */
    private fun merge(root: File, projectId: String, snap: UiCollabSnapshot): String? {
        val remote = snap.files.associate { it.path to it.content.orEmpty() }
        val base = readBase(root)
        val local = listLocal(root)
        val remoteChanged = (remote.keys + base.keys).filter { remote[it] != base[it] }.toSet()
        val localChanged = (local.keys + base.keys).filter { local[it] != base[it] }.toSet()
        remoteChanged.firstOrNull { !safePath(it) }?.let { return "The shared project has a file with an unsafe path: $it" }
        val clash = remoteChanged.intersect(localChanged).filter { local[it] != remote[it] }.sorted()
        if (clash.isNotEmpty()) {
            return "Changed here and on the shared project, so nothing was pulled: " +
                clash.joinToString(", ") + ". Copy your version aside, then pull again."
        }
        val newBase = base.toMutableMap()
        for (path in remoteChanged.sorted()) {
            val text = remote[path]
            if (text == null) {
                File(root, path).delete()
                newBase.remove(path)
            } else {
                writeFile(File(root, path), text)
                newBase[path] = text
            }
        }
        writeBase(root, newBase)
        writeState(root, projectId, snap.headCommit)
        return null
    }

    private data class Change(val path: String, val change: String)

    private fun diff(local: Map<String, String>, base: Map<String, String>): List<Change> {
        val out = ArrayList<Change>()
        for ((path, text) in local) {
            val old = base[path]
            if (old == null) out += Change(path, "added") else if (old != text) out += Change(path, "modified")
        }
        for (path in base.keys) if (path !in local) out += Change(path, "deleted")
        return out.sortedBy { it.path }
    }

    /** Text files of the project, by path. Hidden folders, build output and local settings are left out. */
    private fun listLocal(root: File): Map<String, String> {
        val out = sortedMapOf<String, String>()
        fun walk(dir: File, rel: String) {
            val children = dir.listFiles()?.sortedBy { it.name } ?: return
            for (f in children) {
                if (out.size >= MAX_FILES) return
                val path = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                if (f.isDirectory) {
                    if (f.name.startsWith(".") || f.name in EXCLUDED_DIRS) continue
                    walk(f, path)
                } else if (f.isFile && isSharable(f.name) && f.length() <= MAX_FILE_BYTES) {
                    readText(f)?.let { out[path] = it }
                }
            }
        }
        walk(root, "")
        return out
    }

    private fun readBase(root: File): Map<String, String> {
        val base = File(root, BASE_DIR)
        if (!base.isDirectory) return emptyMap()
        val out = sortedMapOf<String, String>()
        base.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(base).invariantSeparatorsPath
            readText(f)?.let { out[rel] = it }
        }
        return out
    }

    private fun writeBase(root: File, files: Map<String, String>) {
        val base = File(root, BASE_DIR)
        base.deleteRecursively()
        for ((path, text) in files) writeFile(File(base, path), text)
    }

    private fun writeFile(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    private fun readText(f: File): String? {
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return null
        if (bytes.any { it == 0.toByte() }) return null
        return runCatching { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()
    }

    private fun isSharable(name: String): Boolean =
        name !in EXCLUDED_FILES && !name.endsWith(".jks") && !name.endsWith(".keystore")

    /** A path from the shared project may only name a plain file inside the folder. */
    private fun safePath(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/") || path.contains('\\') || path.contains('\u0000')) return false
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return false
        if (parts.dropLast(1).any { it.startsWith(".") || it in EXCLUDED_DIRS }) return false
        return isSharable(parts.last())
    }

    private data class State(val projectId: String, val baseCommit: String?)

    private fun readState(root: File): State? {
        val f = File(root, STATE_FILE)
        if (!f.isFile) return null
        val props = Properties()
        runCatching { f.inputStream().use { props.load(it) } }
        val id = props.getProperty("projectId")?.takeIf { it.isNotBlank() } ?: return null
        return State(id, props.getProperty("baseCommit")?.takeIf { it.isNotBlank() })
    }

    private fun writeState(root: File, projectId: String, baseCommit: String?) {
        val f = File(root, STATE_FILE)
        f.parentFile?.mkdirs()
        val props = Properties()
        props.setProperty("projectId", projectId)
        if (baseCommit != null) props.setProperty("baseCommit", baseCommit)
        f.outputStream().use { props.store(it, null) }
    }

    private companion object {
        const val STATE_FILE = ".codeassist/collab/state.properties"
        const val BASE_DIR = ".codeassist/collab/base"
        const val MAX_FILES = 2000
        const val MAX_FILE_BYTES = 512L * 1024
        val EXCLUDED_DIRS = setOf("build", "out", "node_modules")
        val EXCLUDED_FILES = setOf("local.properties", "keystore.properties")
        const val NOT_LINKED = "Share this folder or join a shared project first"
        const val UNREACHABLE = "Could not reach the shared project. Check your sign-in and connection."
    }
}
