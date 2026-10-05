package com.ispindle.plotter.analysis

import com.ispindle.plotter.data.ExclusionRange
import com.ispindle.plotter.data.Reading

/**
 * Display-time logic for the graph screen's "bad data" marks.
 *
 * Rows in `exclusion_ranges` are stored exactly as the user dragged them:
 * overlaps are kept as separate rows so deleting one mark never destroys a
 * neighbour's span. [coalesce] produces the merged view used for drawing
 * bands and filtering points.
 *
 * Pure JVM (no Android classes) so it is unit-testable like [SeriesClean].
 */
object Exclusions {

    /**
     * Merge overlapping and touching ranges into disjoint spans.
     *
     * Reversed endpoints are normalised (start < end); rows are returned
     * sorted by start; a merged span keeps the id of the first (earliest
     * start) contributing row.
     */
    fun coalesce(ranges: List<ExclusionRange>): List<ExclusionRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges
            .map { if (it.endMs < it.startMs) it.copy(startMs = it.endMs, endMs = it.startMs) else it }
            .sortedBy { it.startMs }
        val out = mutableListOf<ExclusionRange>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && r.startMs <= last.endMs) {
                if (r.endMs > last.endMs) {
                    out[out.size - 1] = last.copy(endMs = r.endMs)
                }
            } else {
                out += r
            }
        }
        return out
    }

    /**
     * Drop readings whose timestamp falls inside any [ranges] row.
     *
     * Closed interval: a reading exactly on a band edge is ignored, matching
     * the band edges drawn on the charts and the BETWEEN semantics the
     * destructive delete-in-range path already uses. The caller must pass
     * ranges for the right device — this does not check deviceId.
     */
    fun filterReadings(readings: List<Reading>, ranges: List<ExclusionRange>): List<Reading> {
        if (ranges.isEmpty()) return readings
        return readings.filter { r ->
            ranges.none { it.startMs <= r.timestampMs && r.timestampMs <= it.endMs }
        }
    }
}