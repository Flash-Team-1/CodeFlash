package dev.ide.core.collab

import dev.ide.ui.collab.CharId
import dev.ide.ui.collab.CollabLive
import dev.ide.ui.collab.CollabText
import dev.ide.ui.collab.SnapChar
import dev.ide.ui.collab.TextOp
import dev.ide.ui.collab.VisibleEdit
import dev.ide.ui.editor.core.EditorSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Live editing of shared files.
 *
 * A file open in a shared project folder joins that project's realtime channel. Each local edit goes out as
 * ops; each remote op lands in the open editor. Everything that touches an editor or the text state runs on
 * the UI thread, so a remote op and a local edit never see stale offsets.
 *
 * Only a file that matches its last synced version goes live, so every collaborator starts from the same
 * character ids. Commits still record what was saved, and the history is unchanged.
 */
internal class CollabLiveManager(
    url: () -> String?,
    token: () -> String?,
) {
    private class LiveDoc(
        val projectId: String,
        val root: String,
        val path: String,
        val session: EditorSession,
        val text: CollabText,
    )

    private val site = UUID.randomUUID().toString()
    private val docs = ConcurrentHashMap<String, LiveDoc>()   // read from pull checks on other threads
    private val keyBySession = HashMap<EditorSession, String>() // UI thread only
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rt = CollabRealtime(url, token, scope) { topic, event, payload ->
        CollabLive.onUi { onRemote(topic, event, payload) }
    }

    /** True while any file of the project folder at [rootPath] is being edited live. */
    fun isLive(rootPath: String): Boolean {
        val root = File(rootPath).canonicalPath
        return docs.values.any { it.root == root }
    }

    fun attach(path: String, session: EditorSession) {
        val file = File(path)
        val root = findRoot(file) ?: return
        val rootPath = root.canonicalPath
        val rel = file.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath
        val projectId = readProjectId(root) ?: return
        val base = File(root, "$BASE_DIR/$rel")
        if (!base.isFile) return
        val baseText = base.readText(Charsets.UTF_8)
        // Unsaved or unpushed changes: stay offline for this file until they are pushed or pulled away.
        if (baseText != session.doc.text || baseText.length > MAX_LIVE_CHARS) return

        val key = keyOf(projectId, rel)
        docs.remove(key)?.let { old ->
            old.session.onLocalEdit = null
            keyBySession.remove(old.session)
        }
        val doc = LiveDoc(projectId, rootPath, rel, session, CollabText.fromBase(site, baseText))
        docs[key] = doc
        keyBySession[session] = key
        session.onLocalEdit = { s, e, text -> onLocalEdit(key, s, e, text) }
        rt.join(topicOf(projectId))
        rt.broadcast(topicOf(projectId), EVENT, buildJsonObject {
            put("type", "sync_req")
            put("path", rel)
        })
    }

    fun detach(path: String, session: EditorSession) {
        val key = keyBySession.remove(session) ?: return
        val doc = docs.remove(key) ?: return
        session.onLocalEdit = null
        if (docs.values.none { it.projectId == doc.projectId }) rt.leave(topicOf(doc.projectId))
    }

    // ---- local changes (UI thread) ----

    private fun onLocalEdit(key: String, s: Int, e: Int, text: String) {
        val doc = docs[key] ?: return
        val ops = try {
            doc.text.localEdit(s, e, text)
        } catch (ex: RuntimeException) {
            // The replica and the buffer disagree. Stop sending rather than spread a broken state.
            stop(key, doc)
            return
        }
        if (ops.isEmpty()) return
        rt.broadcast(topicOf(doc.projectId), EVENT, buildJsonObject {
            put("type", "ops")
            put("path", doc.path)
            put("ops", buildJsonArray { ops.forEach { add(encodeOp(it)) } })
        })
    }

    private fun stop(key: String, doc: LiveDoc) {
        doc.session.onLocalEdit = null
        docs.remove(key)
        keyBySession.remove(doc.session)
    }

    // ---- remote changes (UI thread) ----

    private fun onRemote(topic: String, event: String, payload: JsonObject) {
        if (event != EVENT || !topic.startsWith("collab:")) return
        val projectId = topic.removePrefix("collab:")
        val path = payload["path"]?.jsonPrimitive?.contentOrNull ?: return
        val doc = docs[keyOf(projectId, path)] ?: return
        when (payload["type"]?.jsonPrimitive?.contentOrNull) {
            "ops" -> {
                val ops = payload["ops"] as? JsonArray ?: return
                val edits = ArrayList<VisibleEdit>()
                for (o in ops) {
                    val op = decodeOp(o as? JsonArray ?: continue) ?: continue
                    edits.addAll(doc.text.receive(op))
                }
                for (ed in edits) doc.session.applyRemoteEdit(ed.start, ed.end, ed.text)
                if (doc.text.hasWaiting()) askForState(doc)
            }
            "sync_req" -> sendState(doc)
            "sync" -> {
                val snap = (payload["snap"] as? JsonArray)?.mapNotNull { decodeSnap(it as? JsonArray ?: return@mapNotNull null) }
                    ?: return
                if (doc.text.merge(snap)) replaceWhole(doc.session, doc.text.text())
            }
        }
    }

    /** Asks the others for their state when an op waited too long for something it needs. */
    private fun askForState(doc: LiveDoc) {
        scope.launch {
            delay(3_000)
            CollabLive.onUi {
                if (docs.values.any { it === doc } && doc.text.hasWaiting()) {
                    rt.broadcast(topicOf(doc.projectId), EVENT, buildJsonObject {
                        put("type", "sync_req")
                        put("path", doc.path)
                    })
                }
            }
        }
    }

    private fun sendState(doc: LiveDoc) {
        rt.broadcast(topicOf(doc.projectId), EVENT, buildJsonObject {
            put("type", "sync")
            put("path", doc.path)
            put("snap", buildJsonArray { doc.text.snapshot().forEach { add(encodeSnap(it)) } })
        })
    }

    /** Makes the editor show [newText], changing only the part that differs so the caret stays put. */
    private fun replaceWhole(session: EditorSession, newText: String) {
        val cur = session.doc.text
        var p = 0
        val limit = minOf(cur.length, newText.length)
        while (p < limit && cur[p] == newText[p]) p++
        var q = 0
        while (q < limit - p && cur[cur.length - 1 - q] == newText[newText.length - 1 - q]) q++
        session.applyRemoteEdit(p, cur.length - q, newText.substring(p, newText.length - q))
    }

    // ---- wire format: compact arrays ----

    private fun encodeOp(op: TextOp): JsonElement = when (op) {
        is TextOp.Insert -> buildJsonArray {
            add(JsonPrimitive("i"))
            add(JsonPrimitive(op.id.clock))
            add(JsonPrimitive(op.id.site))
            add(op.ref?.let { JsonPrimitive(it.clock) } ?: JsonNull)
            add(op.ref?.let { JsonPrimitive(it.site) } ?: JsonNull)
            add(JsonPrimitive(op.ch.code))
        }
        is TextOp.Delete -> buildJsonArray {
            add(JsonPrimitive("d"))
            add(JsonPrimitive(op.id.clock))
            add(JsonPrimitive(op.id.site))
        }
    }

    private fun decodeOp(a: JsonArray): TextOp? = runCatching {
        val id = CharId(a[1].jsonPrimitive.long, a[2].jsonPrimitive.content)
        when (a[0].jsonPrimitive.content) {
            "i" -> {
                val ref = if (a[3] is JsonNull) null else CharId(a[3].jsonPrimitive.long, a[4].jsonPrimitive.content)
                TextOp.Insert(id, ref, Char(a[5].jsonPrimitive.int))
            }
            "d" -> TextOp.Delete(id)
            else -> null
        }
    }.getOrNull()

    private fun encodeSnap(c: SnapChar): JsonElement = buildJsonArray {
        add(JsonPrimitive(c.id.clock))
        add(JsonPrimitive(c.id.site))
        add(c.ref?.let { JsonPrimitive(it.clock) } ?: JsonNull)
        add(c.ref?.let { JsonPrimitive(it.site) } ?: JsonNull)
        add(JsonPrimitive(c.ch.code))
        add(JsonPrimitive(if (c.deleted) 1 else 0))
    }

    private fun decodeSnap(a: JsonArray): SnapChar? = runCatching {
        val id = CharId(a[0].jsonPrimitive.long, a[1].jsonPrimitive.content)
        val ref = if (a[2] is JsonNull) null else CharId(a[2].jsonPrimitive.long, a[3].jsonPrimitive.content)
        SnapChar(id, ref, Char(a[4].jsonPrimitive.int), a[5].jsonPrimitive.int == 1)
    }.getOrNull()

    // ---- project folder lookup ----

    private fun findRoot(file: File): File? {
        var dir: File? = file.canonicalFile.parentFile
        while (dir != null) {
            if (File(dir, STATE_FILE).isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    private fun readProjectId(root: File): String? {
        val props = Properties()
        runCatching { File(root, STATE_FILE).inputStream().use { props.load(it) } }
        return props.getProperty("projectId")?.takeIf { it.isNotBlank() }
    }

    private fun keyOf(projectId: String, path: String) = "$projectId|$path"
    private fun topicOf(projectId: String) = "collab:$projectId"

    private companion object {
        const val EVENT = "collab"
        const val STATE_FILE = ".codeassist/collab/state.properties"
        const val BASE_DIR = ".codeassist/collab/base"
        /** Larger files do not go live: the whole state is sent to a newcomer, and that must fit one message. */
        const val MAX_LIVE_CHARS = 60_000
    }
}
