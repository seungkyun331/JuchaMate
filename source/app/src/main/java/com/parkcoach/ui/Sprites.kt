package com.parkcoach.ui

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Pictures of cars made ahead of time from 3D models: one from straight above, and a ring of views
 * from all sides at three heights. A picture is as smooth as the model it was rendered from, which
 * the app could not manage drawing live, and showing one costs almost nothing.
 *
 * Each height is one sheet, [COLUMNS] pictures to a row, starting with the view from dead ahead and
 * going round counter-clockwise. The sheets are in assets/cars; where they came from is in the README.
 */
object Sprites {
    /** How much ground one picture covers, in metres, and its size in pixels. */
    const val CELL = 6.2
    const val SIZE = 320

    /** Views around the car at each height. */
    const val TURNS = 36
    const val COLUMNS = 6

    /** The heights the car was pictured from, in degrees above the ground. */
    val HEIGHTS = intArrayOf(45, 60, 75)

    /** Length and width in metres of the car in each set of pictures. */
    private val sizes = mapOf(
        "ioniq" to (4.657 to 1.897),
        "ioniq_clay" to (4.657 to 1.897),
        "casper_clay" to (3.595 to 1.596),
        "elantra_clay" to (4.675 to 1.808),
        "staria_clay" to (5.253 to 1.971),
    )

    /** Fetches a sheet by name. Set once at start-up; until then every car is drawn the old way. */
    var loader: ((String) -> ImageBitmap?)? = null

    private val sheets = HashMap<String, ImageBitmap?>()

    fun size(set: String): Pair<Double, Double>? = sizes[set]

    /** A sheet, read once and kept. Null when there is no such picture. */
    fun sheet(name: String): ImageBitmap? {
        val load = loader ?: return null
        if (sheets.containsKey(name)) return sheets[name]
        val image = try {
            load(name)
        } catch (e: Exception) {
            null
        }
        sheets[name] = image
        return image
    }
}
