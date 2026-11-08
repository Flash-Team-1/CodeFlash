package dev.ide.ui.collab

/** Identity of one character in a shared file: a Lamport clock plus the site that typed it. */
data class CharId(val clock: Long, val site: String) : Comparable<CharId> {
    override fun compareTo(other: CharId): Int {
        val byClock = clock.compareTo(other.clock)
        return if (byClock != 0) byClock else site.compareTo(other.site)
    }
}

/** One change to a shared file, as it travels between collaborators. */
sealed class TextOp {
    /** [ch] goes right after [ref]; a null [ref] means the start of the file. */
    data class Insert(val id: CharId, val ref: CharId?, val ch: Char) : TextOp()
    data class Delete(val id: CharId) : TextOp()
}

/** A change to apply to an editor buffer, in the buffer's offsets at the moment it is applied. */
data class VisibleEdit(val start: Int, val end: Int, val text: String)

/** One character of a replica with its place in the sequence, enough to rebuild the replica elsewhere. */
data class SnapChar(val id: CharId, val ref: CharId?, val ch: Char, val deleted: Boolean)

/**
 * A replicated text: RGA with tombstones. Every collaborator holds one. A local edit gives the ops to send; a
 * remote op applies once the characters it refers to are here. Every replica reaches the same text whatever
 * order the ops arrive in.
 *
 * The starting text gets fixed ids (clock 1..n, site ""), so replicas that start from the same text agree on
 * every id without talking first.
 *
 * Not thread-safe: the live layer calls it from the UI thread only.
 */
class CollabText private constructor(private val site: String) {
    private class Elem(val id: CharId, val ref: CharId?, val ch: Char, var deleted: Boolean)

    private val elems = ArrayList<Elem>()
    private var clock = 0L
    private val waiting = ArrayList<TextOp>()

    companion object {
        /** A replica that starts from [text]. Every collaborator must start from this same text. */
        fun fromBase(site: String, text: String): CollabText {
            val t = CollabText(site)
            var prev: CharId? = null
            for (i in text.indices) {
                val id = CharId(i + 1L, "")
                t.elems.add(Elem(id, prev, text[i], false))
                prev = id
            }
            t.clock = text.length.toLong()
            return t
        }
    }

    /** The visible text. */
    fun text(): String {
        val sb = StringBuilder(elems.size)
        for (e in elems) if (!e.deleted) sb.append(e.ch)
        return sb.toString()
    }

    /** True while some remote op waits for a character it refers to. */
    fun hasWaiting(): Boolean = waiting.isNotEmpty()

    private fun indexOf(id: CharId): Int {
        for (i in elems.indices) if (elems[i].id == id) return i
        return -1
    }

    /** Element index of each visible character, in order. */
    private fun visibleIndices(): IntArray {
        var n = 0
        for (e in elems) if (!e.deleted) n++
        val out = IntArray(n)
        var k = 0
        for (i in elems.indices) if (!elems[i].deleted) out[k++] = i
        return out
    }

    /** Visible characters before element [index]. */
    private fun visibleBefore(index: Int): Int {
        var n = 0
        for (i in 0 until index) if (!elems[i].deleted) n++
        return n
    }

    /** Places a new character by the RGA rule. Returns its element index, or -1 if this replica already has it. */
    private fun insertAt(id: CharId, ref: CharId?, ch: Char): Int {
        if (indexOf(id) >= 0) return -1
        var pos = 0
        if (ref != null) {
            val r = indexOf(ref)
            check(r >= 0) { "reference character is missing" }
            pos = r + 1
        }
        // Skip characters that were placed after [ref] with a higher id: they take priority.
        while (pos < elems.size && elems[pos].id > id) pos++
        elems.add(pos, Elem(id, ref, ch, false))
        if (id.clock > clock) clock = id.clock
        return pos
    }

    /** Replaces visible characters [start, end) with [text] on this replica. Returns the ops to send. */
    fun localEdit(start: Int, end: Int, text: String): List<TextOp> {
        val ops = ArrayList<TextOp>()
        val vis = visibleIndices()
        require(start in 0..end && end <= vis.size) { "edit is outside the text" }
        for (k in start until end) {
            val e = elems[vis[k]]
            e.deleted = true
            ops.add(TextOp.Delete(e.id))
        }
        if (text.isNotEmpty()) {
            var ref: CharId? = if (start == 0) null else elems[visibleIndices()[start - 1]].id
            for (ch in text) {
                clock += 1
                val id = CharId(clock, site)
                insertAt(id, ref, ch)
                ops.add(TextOp.Insert(id, ref, ch))
                ref = id
            }
        }
        return ops
    }

    /** Applies a remote op now, or keeps it until the characters it refers to arrive. Returns the buffer edits made. */
    fun receive(op: TextOp): List<VisibleEdit> {
        waiting.add(op)
        val out = ArrayList<VisibleEdit>()
        var progress = true
        while (progress) {
            progress = false
            val iter = waiting.iterator()
            while (iter.hasNext()) {
                val o = iter.next()
                if (!ready(o)) continue
                iter.remove()
                val edit = applyOp(o)
                if (edit != null) out.add(edit)
                progress = true
            }
        }
        return out
    }

    private fun ready(o: TextOp): Boolean = when (o) {
        is TextOp.Insert -> o.ref == null || indexOf(o.ref) >= 0
        is TextOp.Delete -> indexOf(o.id) >= 0
    }

    private fun applyOp(o: TextOp): VisibleEdit? = when (o) {
        is TextOp.Insert -> {
            val pos = insertAt(o.id, o.ref, o.ch)
            if (pos < 0) null else {
                val off = visibleBefore(pos)
                VisibleEdit(off, off, o.ch.toString())
            }
        }
        is TextOp.Delete -> {
            val i = indexOf(o.id)
            val e = elems[i]
            if (e.deleted) null else {
                val off = visibleBefore(i)
                e.deleted = true
                VisibleEdit(off, off + 1, "")
            }
        }
    }

    /** Every character this replica holds, in order, tombstones included. Sent to a collaborator that asks. */
    fun snapshot(): List<SnapChar> = elems.map { SnapChar(it.id, it.ref, it.ch, it.deleted) }

    /** Takes in the characters and deletions of [snap] this replica lacks. Returns true if the visible text changed. */
    fun merge(snap: List<SnapChar>): Boolean {
        val before = text()
        for (s in snap) {
            val i = indexOf(s.id)
            if (i < 0) {
                // A snapshot lists each character after its reference, so the reference is here by now.
                if (s.ref != null && indexOf(s.ref) < 0) continue
                val pos = insertAt(s.id, s.ref, s.ch)
                if (s.deleted && pos >= 0) elems[pos].deleted = true
            } else if (s.deleted) {
                elems[i].deleted = true
            }
        }
        return text() != before
    }
}
