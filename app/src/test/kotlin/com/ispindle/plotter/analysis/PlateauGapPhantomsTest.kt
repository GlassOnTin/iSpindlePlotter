package com.ispindle.plotter.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-capture regression for gap-straddling false "pause" plateaus.
 *
 * The window-based slope detector reads near-zero slope across any window
 * that contains only a small cluster of post-gap readings — extrapolating
 * a flat curve across the empty side of the window. That fabricated a
 * "Mid pause" stripe on the chart at every logging-gap edge.
 *
 * The brew2 late capture (~190 h, two intra-episode gaps: 16 h and 78 h)
 * is the natural test bed. Any Mid plateau whose reported extent lies
 * inside the gap, or starts/ends within ~1 h of a gap edge with the
 * gap covering most of the slope window, is a phantom.
 */
class PlateauGapPhantomsTest {

    /**
     * Every detected Mid plateau must be backed by actual readings spanning
     * at least half the slope-window width — i.e. it cannot sit mostly
     * inside a logging gap.
     */
    @Test fun `no Mid plateau is reported inside a logging gap on brew2`() {
        val (h, sg) = loadHoursSg("ferment_brew2_late.csv")
        val plats = PlateauDetector.detect(h, sg)
        // The 16 h gap (h ≈ 75.13 → 91.21) and the 78 h gap (h ≈ 107.29 →
        // 185.43) are the regression zones. A pre-fix detector emitted
        // phantom Mid plateaus at 89.75–93.25 and 184.25–186.75.
        val phantomZones = listOf(
            89.0 to 91.0,    // entry to the 16 h gap end
            184.0 to 185.4,  // entry to the 78 h gap end
            107.0 to 108.0   // edge of the 78 h gap start
        )
        for (p in plats.filter { it.kind == Plateau.Kind.Mid }) {
            for ((lo, hi) in phantomZones) {
                assertFalse(
                    "phantom Mid plateau ${p.startH}..${p.endH} sits in gap zone $lo..$hi",
                    p.startH in lo..hi || p.endH in lo..hi
                )
            }
        }
    }

    /**
     * Lock the post-fix plateau count: the same 190 h capture (one real
     * diauxic pause at ~44 h, then 150 h of gradual slowing toward FG)
     * should surface that one Mid and the end-of-data Tail — not the 12
     * the pre-fix detector found (4 gap-edge phantoms + 5 sub-regions of
     * the slowing tail mis-read as pauses + 2 duplicate-windows + the
     * real pause and tail). Two-or-three accommodates the asymptotic
     * Tail occasionally splitting at the noise boundary.
     */
    @Test fun `brew2 capture surfaces only the real diauxic pause and the tail`() {
        val (h, sg) = loadHoursSg("ferment_brew2_late.csv")
        val plats = PlateauDetector.detect(h, sg)
        assertTrue(
            "expected 2..3 plateaus on brew2; got ${plats.size}: $plats",
            plats.size in 2..3
        )
        // The 1.0367 diauxic pause and the 1.017 tail must both survive.
        assertTrue(
            "expected a Mid plateau near sg=1.037; got $plats",
            plats.any { it.kind == Plateau.Kind.Mid && it.sg in 1.034..1.040 }
        )
        assertTrue(
            "expected a Tail plateau near sg=1.017; got $plats",
            plats.any { it.kind == Plateau.Kind.Tail && it.sg in 1.014..1.019 }
        )
    }

    /**
     * The real diauxic pause at ~1.037 in the prior paused capture must
     * still be detected — guards against the coverage check and the data-
     * clip rejecting borderline-2.5 h real flat patches.
     */
    @Test fun `the real near-1_037 diauxic pause is still detected on the paused capture`() {
        val (h, sg) = loadHoursSg("ferment_capture_brew2_paused.csv")
        val plats = PlateauDetector.detect(h, sg)
        val pause = plats.firstOrNull { it.sg in 1.034..1.040 }
        assertTrue(
            "expected a Mid plateau near sg=1.037; got $plats",
            pause != null && pause.kind == Plateau.Kind.Mid
        )
    }

    private fun loadHoursSg(name: String): Pair<DoubleArray, DoubleArray> {
        val rsrc = javaClass.classLoader!!.getResourceAsStream(name)!!
        val lines = rsrc.bufferedReader().readLines()
        val header = lines.first().split(',')
        val tIdx = header.indexOf("timestamp_ms")
        val sgIdx = header.indexOf("computed_gravity")
        val ts = mutableListOf<Long>(); val sgs = mutableListOf<Double>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val p = line.split(',')
            ts += p[tIdx].toDouble().toLong(); sgs += p[sgIdx].toDouble()
        }
        val t0 = ts.first()
        val h = DoubleArray(ts.size) { (ts[it] - t0) / 3_600_000.0 }
        return h to sgs.toDoubleArray()
    }
}
