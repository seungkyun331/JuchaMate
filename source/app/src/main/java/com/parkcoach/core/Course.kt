package com.parkcoach.core

/** A handful of lessons that belong together. */
class Chapter(val title: String, val ids: List<String>)

/**
 * The lessons in the order they are meant to be taken, easiest first. Each one is gone through
 * three times with less help each time: every instruction, then only the path, then alone. The
 * last is the test, and passing it opens the next lesson.
 */
object Course {
    val chapters: List<Chapter> = listOf(
        Chapter("후진 주차 기본", listOf("basic", "facing_cars", "facing_wall")),
        Chapter("벽과 기둥", listOf("end_wall", "pillar", "garage")),
        Chapter("좁은 곳, 까다로운 옆 차", listOf("narrow_aisle", "tight_slot", "sticking_out", "big_neighbour")),
        Chapter("전면과 사선", listOf("nose_in", "angled60", "angled45")),
        Chapter("평행 주차", listOf("parallel", "parallel_tight")),
    )

    val order: List<String> = chapters.flatMap { it.ids }

    /** Stars it takes, unguided, to pass a lesson. */
    const val PASS = 2

    /** A lesson can be started once the one before it has been passed. The first always can. */
    fun open(id: String, passed: Set<String>): Boolean {
        val i = order.indexOf(id)
        return i <= 0 || order[i - 1] in passed
    }

    /** The lesson that passing [id] opens, if there is one after it. */
    fun after(id: String): String? = order.getOrNull(order.indexOf(id) + 1).takeIf { id in order }

    /** "2-1": chapter and lesson, counted from one. */
    fun label(id: String): String {
        chapters.forEachIndexed { c, chapter ->
            val i = chapter.ids.indexOf(id)
            if (i >= 0) return "${c + 1}-${i + 1}"
        }
        return ""
    }
}

/** Somewhere the driver found good for practising, kept so the car's navigation can lead back to it. */
data class Place(val name: String, val lat: Double, val lng: Double) {
    fun encode(): String = "$name|$lat|$lng"

    companion object {
        fun decode(line: String): Place? = try {
            val p = line.split("|")
            Place(p[0], p[1].toDouble(), p[2].toDouble())
        } catch (e: Exception) {
            null
        }
    }
}
