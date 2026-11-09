package dev.ide.ui.collab

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollabTextTest {
    private fun randomText(r: Random, alphabet: String, len: IntRange): String {
        val n = r.nextInt(len.first, len.last + 1)
        return buildString { repeat(n) { append(alphabet[r.nextInt(alphabet.length)]) } }
    }

    @Test
    fun singleSiteEditsMatchPlainText() {
        for (seed in 0 until 200) {
            val r = Random(seed)
            val t = CollabText.fromBase("a", "seed text")
            var ref = "seed text"
            repeat(60) {
                val n = ref.length
                val s: Int
                val e: Int
                val txt: String
                if (r.nextDouble() < 0.6 || n == 0) {
                    s = r.nextInt(n + 1)
                    e = s
                    txt = randomText(r, "pq\n ", 1..3)
                } else {
                    s = r.nextInt(n)
                    e = minOf(n, s + r.nextInt(1, 5))
                    txt = ""
                }
                t.localEdit(s, e, txt)
                ref = ref.substring(0, s) + txt + ref.substring(e)
                assertEquals(ref, t.text(), "seed $seed")
            }
        }
    }

    @Test
    fun threeReplicasConvergeWhenOpsArriveOutOfOrderAndTwice() {
        for (seed in 0 until 300) {
            val r = Random(seed)
            val names = listOf("s0", "s1", "s2")
            val reps = names.associateWith { CollabText.fromBase(it, "hello world") }
            val inbox = names.associateWith { ArrayList<TextOp>() }
            repeat(300) {
                val name = names[r.nextInt(names.size)]
                val d = reps.getValue(name)
                val n = d.text().length
                val ops: List<TextOp> = if (r.nextDouble() < 0.7 || n == 0) {
                    val s = r.nextInt(n + 1)
                    d.localEdit(s, s, randomText(r, "abcxyz \n", 1..3))
                } else {
                    val s = r.nextInt(n)
                    d.localEdit(s, minOf(n, s + r.nextInt(1, 4)), "")
                }
                for (other in names) if (other != name) inbox.getValue(other).addAll(ops)
                for (other in names) {
                    val box = inbox.getValue(other)
                    val k = r.nextInt(box.size + 1)
                    val batch = box.subList(0, k).toMutableList()
                    box.subList(0, k).clear()
                    batch.shuffle(r)
                    for (op in batch) {
                        reps.getValue(other).receive(op)
                        if (r.nextDouble() < 0.2) reps.getValue(other).receive(op)
                    }
                }
            }
            for (other in names) {
                val rest = inbox.getValue(other).toMutableList()
                rest.shuffle(r)
                for (op in rest) reps.getValue(other).receive(op)
                assertTrue(!reps.getValue(other).hasWaiting(), "seed $seed: $other still waits")
            }
            val texts = reps.values.map { it.text() }.toSet()
            assertEquals(1, texts.size, "seed $seed diverged: $texts")
        }
    }

    @Test
    fun newcomerMergesSnapshotsAndAllReplicasAgree() {
        val a = CollabText.fromBase("a", "base text")
        val b = CollabText.fromBase("b", "base text")
        a.localEdit(0, 4, "BASE")
        b.localEdit(9, 9, "!")
        val c = CollabText.fromBase("c", "base text")
        c.merge(a.snapshot())
        c.merge(b.snapshot())
        b.merge(a.snapshot())
        a.merge(b.snapshot())
        assertEquals("BASE text!", a.text())
        assertEquals(a.text(), b.text())
        assertEquals(a.text(), c.text())
    }
}
