package com.parkcoach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.parkcoach.core.Box
import com.parkcoach.core.Gear
import com.parkcoach.core.Kind
import com.parkcoach.core.Lot
import com.parkcoach.core.Obstacle
import com.parkcoach.core.Opposite
import com.parkcoach.core.Pose
import com.parkcoach.core.Sample
import com.parkcoach.core.Side
import com.parkcoach.core.SlotType
import com.parkcoach.core.Spot
import com.parkcoach.core.Vec
import com.parkcoach.core.VehicleSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * The marks shown over a lot while it is being built: a plus on each place something can be put and
 * a cross where something already stands. It also remembers where the marks landed on screen, so a
 * touch can be matched to one.
 */
class EditLayer {
    /** The place whose choices are being shown, if any. */
    var open: Spot? = null
    private val marks = HashMap<Spot, Offset>()
    private var reach = 0f

    private val bodies = HashMap<Spot, Offset>()

    fun put(spot: Spot, at: Offset, radius: Float) {
        marks[spot] = at
        reach = radius
    }

    /** A car stands on [spot], drawn around [at]: touching the car itself opens its choices. */
    fun body(spot: Spot, at: Offset?) {
        if (at == null) bodies.remove(spot) else bodies[spot] = at
    }

    /** Where the car on [spot] was last drawn, if one stands there. */
    fun seat(spot: Spot): Offset? = bodies[spot]

    /** The car under a finger at [p], if it missed every mark. */
    fun car(p: Offset): Spot? = bodies.entries
        .map { it.key to hypot(it.value.x - p.x, it.value.y - p.y) }
        .filter { it.second < reach * 3.2f }
        .minByOrNull { it.second }?.first

    /** Where the mark for [spot] was last drawn. */
    fun where(spot: Spot): Offset? = marks[spot]

    /** The mark under a finger at [p], allowing for a finger being wider than the mark. */
    fun at(p: Offset): Spot? = marks.entries
        .map { it.key to hypot(it.value.x - p.x, it.value.y - p.y) }
        .filter { it.second < reach * 1.6f }
        .minByOrNull { it.second }?.first
}

/** Everything one picture of the lot needs. Built fresh each frame from the engine. */
class SceneData(
    val spec: VehicleSpec,
    val pose: Pose,
    val wheelDeg: Double,
    val lot: Lot?,
    val trail: List<Sample> = emptyList(),
    val ghost: List<Sample> = emptyList(),
    val path: List<Pose> = emptyList(),
    val box: Pose? = null,
    val hit: Obstacle? = null,
    /** 0 looks straight down; 1 is the lowest three-quarter view. */
    val tilt: Float = 0f,
    /** Radians the view has been turned around the scene. */
    val spin: Float = 0f,
    /** Changes every frame, so the picture is redrawn even though the engine object is the same. */
    val frame: Int = 0,
    /** Draw the parking-sensor arcs at the car's corners. */
    val sensors: Boolean = false,
    /** Height in dp at the top that something is laid over. The scene is framed in the part below it. */
    val topInset: Float = 0f,
    /**
     * Width in dp at the left that the driver's column floats over. The picture still runs to the
     * edge beneath it, but the car and the lot are centred in the part to the right.
     */
    val leftInset: Float = 0f,
    /** Keep the car in the middle of what can be seen and let the lot move beneath it. */
    val follow: Boolean = false,
    /** The far edge of the ground the car has already been on, when guidance is being kept inside it. */
    val limit: Box? = null,
    /** Set while the lot is being built: draws the plus and cross marks. */
    val edit: EditLayer? = null,
)

private fun tone(dark: Long, bright: Long): Color = Color(if (Palette.light) bright else dark)

// Every colour in the picture comes in a night and a day version. Both are quiet on purpose: pale
// ground, matte clay scenery, and colour kept for the few things that mean something.
private val Sky: Color get() = tone(0xFF1B1C20, 0xFFFBFBFC)
private val Floor: Color get() = tone(0xFF131417, 0xFFEEEFF2)
private val Paint: Color get() = tone(0xFF5C5F66, 0xFFC3C6CC)
private val Ink: Color get() = tone(0xFFFFFFFF, 0xFF1D1D1F)
private val Clay: Color get() = tone(0xFF585B62, 0xFFD6D8DC)
private val Pearl: Color get() = tone(0xFFECEEF1, 0xFF202226)
private val GlassDark: Color get() = tone(0xFF0C0F14, 0xFF08090B)
private val GlassRoof: Color get() = tone(0xFF14171C, 0xFF0C0D10)
private val Tyre = Color(0xFF0C0D0F)
private val Rim: Color get() = tone(0xFF9A9EA6, 0xFF70747C)
private val Concrete: Color get() = tone(0xFF4C4F56, 0xFFD2D5D9)
private val Guide: Color get() = tone(0xFF30D158, 0xFF1FB04E)
private val Ice: Color get() = tone(0xFF9CCBFF, 0xFF0A84FF)
private val Amber: Color get() = tone(0xFFFFB866, 0xFFF07800)
private val Brake = Color(0xFFFF453A)

/**
 * A pinhole camera looking down at [focus]. [up] is the direction on the ground that points away
 * from the viewer. From straight overhead it still has perspective, so tall things lean away from
 * the centre the way they do in a real surround-view picture.
 */
private class Cam(focus: Vec, up: Double, distance: Double, val pitch: Double, private val w: Float, private val h: Float) {
    private val ux = cos(up)
    private val uy = sin(up)
    private val cp = cos(pitch)
    private val sp = sin(pitch)
    val cx = focus.x - ux * distance * cp
    val cy = focus.y - uy * distance * cp
    val cz = distance * sp
    private val focal = w * FOCAL
    private val reach = distance

    /** How far something lies beyond what is being looked at, 0 to 1: distant things take the colour of the air. */
    fun haze(p: Vec): Float = (((depth(p) - reach - 5.0) / 30.0).coerceIn(0.0, 1.0) * 0.6).toFloat()

    /** Pixels per metre on the ground near the focus. */
    val scale: Float = (focal / distance).toFloat()

    fun depth(p: Vec, z: Double = 0.0): Double = ((p.x - cx) * ux + (p.y - cy) * uy) * cp - (z - cz) * sp

    fun at(p: Vec, z: Double = 0.0): Offset? {
        val vx = p.x - cx
        val vy = p.y - cy
        val vz = z - cz
        val along = vx * ux + vy * uy
        val zc = along * cp - vz * sp
        if (zc < 1.0) return null
        val xc = vx * uy - vy * ux
        val yc = along * sp + vz * cp
        return Offset(w / 2 + (focal * xc / zc).toFloat(), h / 2 - (focal * yc / zc).toFloat())
    }

    fun depthOf(x: Double, y: Double, z: Double): Double = ((x - cx) * ux + (y - cy) * uy) * cp - (z - cz) * sp

    /** Projects straight into two arrays, for the thousands of points a car body is made of. */
    fun into(x: Double, y: Double, z: Double, sx: FloatArray, sy: FloatArray, i: Int): Boolean {
        val vx = x - cx
        val vy = y - cy
        val vz = z - cz
        val along = vx * ux + vy * uy
        val zc = along * cp - vz * sp
        if (zc < 1.0) return false
        sx[i] = w / 2 + (focal * (vx * uy - vy * ux) / zc).toFloat()
        sy[i] = h / 2 - (focal * (along * sp + vz * cp) / zc).toFloat()
        return true
    }

    companion object {
        const val FOCAL = 1.1f
    }
}

/** The moving picture: floor, markings, paths, cars and walls, drawn far to near. */
@Composable
fun SceneView(data: SceneData, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val lot = data.lot
        val car = data.spec.body(data.pose)
        val aspect = size.width / size.height

        // Frame the whole situation when there is one; otherwise ride above the car.
        val baseUp: Double
        val focus: Vec
        val tall: Double
        if (lot != null) {
            val perpendicular = lot.config.type != SlotType.PARALLEL
            baseUp = lot.inward
            val out = Vec(-cos(baseUp), -sin(baseUp))
            val depth = if (perpendicular) lot.slot.length else lot.slot.width
            val lane = min(lot.config.aisle, 8.0)
            val beyond = if (lot.config.opposite == Opposite.CARS) (if (perpendicular) 5.2 else 2.4) else 0.8
            val whole = depth + lane + beyond
            val middle = lot.slot.c + out * (whole / 2 - depth / 2)
            if (data.follow) {
                // The scale is set by the lot, not by where the car is, so nothing swells and shrinks
                // as the car goes back and forth: from anywhere in the lane the space is in view.
                focus = car.c
                tall = max(16.0, max(whole + 3.5, 2 * (depth + lane * 0.6 + 0.8)))
            } else {
                focus = middle + (car.c - middle) * 0.25
                tall = max(whole + 3.5, 2 * ((car.c - focus).len() + 4.5))
            }
        } else {
            // Nothing but the car yet: it sits in the middle of what can be seen.
            baseUp = data.pose.h
            focus = car.c
            tall = 22.0
        }
        // Whatever is laid over the top of the picture must not hide the space being parked in:
        // the view is widened and moved so the situation sits in the part that stays clear.
        val covered = (data.topInset * density / size.height).coerceIn(0f, 0.45f)
        val kept = (data.leftInset * density).coerceIn(0f, size.width * 0.45f)
        // With a column floating over the left, what is left must still be a little wider than it is
        // tall, or the car could leave it sideways.
        val clear = (size.width - kept) / (size.height * (1 - covered))
        val up = baseUp + data.spin
        var need = tall
        if (lot != null && data.follow) {
            // Only a car that strays a long way makes the view draw back, and then no more than it
            // takes to keep the space in the picture.
            val ahead = Vec(cos(up), sin(up))
            val across = Vec(sin(up), -cos(up))
            for (p in lot.slot.corners()) {
                val d = p - focus
                need = max(need, max(2 * (abs(d.dot(ahead)) + 1.0), 2 * (abs(d.dot(across)) + 1.0) / clear))
            }
        } else if (kept > 0f) {
            need = tall * max(1.0, 1.15 / clear)
        }
        val seen = need / (1 - covered)
        val distance = (seen * Cam.FOCAL * aspect).coerceIn(24.0, 110.0)
        val pitch = Math.toRadians(88.0 - 46.0 * data.tilt.coerceIn(0f, 1f))
        val metresPerPixel = distance / (size.width * Cam.FOCAL)
        val centre = focus + Vec(cos(up), sin(up)) * (seen * covered / 2) - Vec(sin(up), -cos(up)) * (kept / 2 * metresPerPixel)
        val cam = Cam(centre, up, distance, pitch, size.width, size.height)
        // One lamp, high and to one side of the lot, fixed to the ground so shading turns with the view.
        val light = Vec(cos(baseUp + 2.25), sin(baseUp + 2.25))

        floor(cam, focus)
        if (lot == null) {
            grid(cam, car.c)
        } else {
            for ((a, b) in lot.lines) stripe(cam, listOf(a, b), Paint, 0.12f)
            bay(cam, lot)
        }

        data.box?.let { box ->
            val b = data.spec.body(box)
            val shape = place(Pose(b.c.x, b.c.y, b.h), hull(b.length, b.width, 5.0, 36, 0.12, 0.07))
            flat(cam, shape, 0.0, Guide.copy(alpha = 0.10f))
            stripe(cam, shape + shape.first(), Guide.copy(alpha = 0.9f), 0.045f)
        }
        data.limit?.let { edge ->
            // A quiet dashed line across the lane: this far the car has been, and no further.
            val along = Pose(edge.c.x, edge.c.y, edge.h)
            stripe(cam, listOf(along.toWorld(Vec(-16.0, 0.0)), along.toWorld(Vec(16.0, 0.0))), Ink.copy(alpha = 0.26f), 0.05f, dashed = true)
        }
        tracks(cam, data.spec, data.ghost, faint = true)
        tracks(cam, data.spec, data.trail, faint = false)
        corridor(cam, data.spec, data.path, data.pose)

        // Solid things, farthest first, so nearer ones cover them.
        val solids = ArrayList<Pair<Double, () -> Unit>>()
        if (lot != null) {
            // Shadows lie on the ground, under everything that stands on it.
            for (o in lot.obstacles) if (o.kind != Kind.CAR && o.kind != Kind.CONE) footing(cam, o.box)
            for (o in lot.obstacles) {
                val struck = o === data.hit
                if (o.kind == Kind.CAR || o.kind == Kind.CONE) {
                    solids.add(cam.depth(o.box.c) to { obstacle(cam, o, struck, light) })
                } else {
                    // A long wall is nearer than some cars and farther than others, so it cannot be
                    // put in one place in the order. It is drawn a short length at a time, and each
                    // length takes its turn among the cars beside it.
                    val fade = cam.haze(o.box.c)
                    val pieces = lengths(o.box)
                    pieces.forEachIndexed { i, piece ->
                        solids.add(cam.depth(piece.c) to { block(cam, piece, o.kind, struck, fade, light, o.box.length >= o.box.width, i == 0, i == pieces.size - 1) })
                    }
                }
            }
        }
        solids.add(cam.depth(car.c) to { ego(cam, data, light) })
        solids.sortByDescending { it.first }
        for (s in solids) s.second()
        if (lot != null && data.sensors) radar(cam, lot, car)
        // While the car is out of the space, a mark says which one it is.
        if (lot != null && (car.c - lot.slot.c).len() > 2.2) badge(cam, lot.slot.c)
        if (lot != null && data.edit != null) marks(cam, lot, data.edit)

        // Darken the corners so the eye stays on the car. By day the floor is left clean.
        val reach = hypot(size.width, size.height) / 2
        if (!Palette.light) drawRect(
            Brush.radialGradient(
                listOf(Color.Transparent, Color.Transparent, Color(0x73000000)),
                center = Offset(size.width / 2, size.height / 2),
                radius = reach * 1.05f,
            ),
        )
    }
}

// ---------------------------------------------------------------------- ground

private fun DrawScope.floor(cam: Cam, focus: Vec) {
    drawRect(Brush.verticalGradient(listOf(Sky, Floor)))
    val centre = cam.at(focus) ?: return
    val reach = size.minDimension * 0.9f
    drawCircle(
        Brush.radialGradient(
            if (Palette.light) listOf(Color(0xFFFFFFFF), Color(0x00FFFFFF)) else listOf(Color(0x2EC3CEE2), Color(0x00C3CEE2)),
            center = centre,
            radius = reach,
        ),
        radius = reach,
        center = centre,
    )
}

private fun DrawScope.grid(cam: Cam, around: Vec) {
    val x0 = Math.floor(around.x / 2.0) * 2.0
    val y0 = Math.floor(around.y / 2.0) * 2.0
    val color = Ink.copy(alpha = 0.06f)
    for (i in -14..14) {
        stripe(cam, listOf(Vec(x0 + i * 2.0, y0 - 28.0), Vec(x0 + i * 2.0, y0 + 28.0)), color, 0.03f)
        stripe(cam, listOf(Vec(x0 - 28.0, y0 + i * 2.0), Vec(x0 + 28.0, y0 + i * 2.0)), color, 0.03f)
    }
}

/** The space to park in: a tinted field with one clean edge. */
private fun DrawScope.bay(cam: Cam, lot: Lot) {
    val s = lot.slot
    val shape = place(Pose(s.c.x, s.c.y, s.h), hull(s.length - 0.20, s.width - 0.20, 14.0, 44, 0.0, 0.0))
    flat(cam, shape, 0.0, Guide.copy(alpha = if (Palette.light) 0.22f else 0.15f))
    if (!Palette.light) stripe(cam, shape + shape.first(), Guide.copy(alpha = 0.14f), 0.34f)
    stripe(cam, shape + shape.first(), Guide, 0.07f)
}

/**
 * A plus on every empty place around the space, and a cross over whatever already stands in one.
 * The place being chosen for is ringed.
 */
private fun DrawScope.marks(cam: Cam, lot: Lot, layer: EditLayer) {
    val r = max(22f, 0.56f * cam.scale)
    for ((spot, where) in lot.spots) {
        val taken = when (spot) {
            Spot.LEFT -> lot.config.left != Side.EMPTY
            Spot.RIGHT -> lot.config.right != Side.EMPTY
            Spot.OPPOSITE -> lot.config.opposite != Opposite.OPEN
        }
        // A cross floats above the thing it removes; a plus lies on the empty ground.
        val c = cam.at(where, if (taken) 1.9 else 0.0) ?: continue
        layer.put(spot, c, r)
        if (spot != Spot.OPPOSITE) layer.body(spot, if (lot.config.side(spot) == Side.CAR) cam.at(where, 0.5) else null)
        drawCircle(Color.Black.copy(alpha = 0.12f), r * 1.05f, Offset(c.x, c.y + r * 0.10f))
        if (layer.open == spot) drawCircle(Ice.copy(alpha = 0.30f), r * 1.45f, c)
        drawCircle(if (taken) Ink.copy(alpha = 0.80f) else Ice, r, c)
        val arm = r * 0.42f
        val width = r * 0.17f
        val sign = if (Palette.light || !taken) Color.White else Color.Black
        if (taken) {
            val d = arm * 0.72f
            drawLine(sign, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), width, StrokeCap.Round)
            drawLine(sign, Offset(c.x - d, c.y + d), Offset(c.x + d, c.y - d), width, StrokeCap.Round)
        } else {
            drawLine(sign, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), width, StrokeCap.Round)
            drawLine(sign, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), width, StrokeCap.Round)
        }
    }
}

/** A round "P" standing on the space to park in. */
private fun DrawScope.badge(cam: Cam, at: Vec) {
    val c = cam.at(at) ?: return
    val r = max(15f, 0.62f * cam.scale)
    drawCircle(Color.Black.copy(alpha = 0.10f), r * 1.06f, Offset(c.x, c.y + r * 0.10f))
    drawCircle(Guide, r, c)
    // The letter as one stroke: up the stem, then round the bowl.
    val h = r * 0.98f
    val x = c.x - r * 0.24f
    val top = c.y - h / 2
    val bowl = h * 0.28f
    val flat = r * 0.20f
    val path = Path()
    path.moveTo(x, top + h)
    path.lineTo(x, top)
    path.lineTo(x + flat, top)
    for (i in 0..10) {
        val a = -PI / 2 + PI * i / 10
        path.lineTo(x + flat + bowl * cos(a).toFloat(), top + bowl + bowl * sin(a).toFloat())
    }
    path.lineTo(x, top + 2 * bowl)
    drawPath(path, Color.White, style = Stroke(width = r * 0.19f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// ---------------------------------------------------------------------- drawing helpers

private fun toPath(points: List<Offset>, close: Boolean): Path {
    val path = Path()
    points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
    if (close) path.close()
    return path
}

private fun DrawScope.flat(cam: Cam, shape: List<Vec>, z: Double, color: Color) {
    val points = shape.map { cam.at(it, z) ?: return }
    drawPath(toPath(points, true), color)
}

/** A line on the ground whose width is given in metres, so it thins with distance like paint does. */
private fun DrawScope.stripe(cam: Cam, points: List<Vec>, color: Color, metres: Float, z: Double = 0.0, dashed: Boolean = false) {
    val projected = points.mapNotNull { cam.at(it, z) }
    if (projected.size < 2) return
    val width = max(1.5f, metres * cam.scale)
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(width * 3.5f, width * 3f), 0f) else null
    drawPath(
        toPath(projected, false),
        color,
        style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect),
    )
}

/** A line of light: a wide soft halo, a tighter one, then a bright core. */
private fun DrawScope.glow(cam: Cam, points: List<Vec>, color: Color, core: Float, strength: Float = 1f, z: Double = 0.0) {
    stripe(cam, points, color.copy(alpha = 0.10f * strength), core * 7f, z)
    stripe(cam, points, color.copy(alpha = 0.22f * strength), core * 3f, z)
    stripe(cam, points, color.copy(alpha = min(1f, 0.95f * strength)), core, z)
}

private fun shade(c: Color, k: Float): Color =
    Color((c.red * k).coerceIn(0f, 1f), (c.green * k).coerceIn(0f, 1f), (c.blue * k).coerceIn(0f, 1f), c.alpha)

private fun mix(a: Color, b: Color, t: Float): Color =
    Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha)

/**
 * The outline of a car seen from above, about its own centre: a rounded shape that is squarer for a
 * higher [power] and pinched toward the nose and tail. Starts at the nose and runs counter-clockwise.
 */
private fun hull(length: Double, width: Double, power: Double, n: Int, nose: Double, tail: Double): List<Vec> {
    // The same few shapes are asked for every frame, so each is worked out once and kept.
    val key = listOf(length, width, power, n.toDouble(), nose, tail)
    return shapes.getOrPut(key) { hullOf(length, width, power, n, nose, tail) }
}

private val shapes = HashMap<List<Double>, List<Vec>>()

private fun hullOf(length: Double, width: Double, power: Double, n: Int, nose: Double, tail: Double): List<Vec> {
    val out = ArrayList<Vec>(n)
    for (i in 0 until n) {
        val t = 2 * PI * i / n
        val c = cos(t)
        val s = sin(t)
        val x = sign(c) * abs(c).pow(2 / power)
        val y = sign(s) * abs(s).pow(2 / power) * (1 - (if (x > 0) nose else tail) * x * x)
        out.add(Vec(x * length / 2, y * width / 2))
    }
    return out
}

private fun place(f: Pose, shape: List<Vec>, ahead: Double = 0.0): List<Vec> = shape.map { f.toWorld(Vec(it.x + ahead, it.y)) }

/**
 * The skin between two outlines at different heights. Each strip is lit by how squarely it faces the
 * lamp; [gloss] adds a highlight where it faces it head on, which is what makes glass read as glass.
 */
private fun DrawScope.loft(cam: Cam, a: List<Vec>, za: Double, b: List<Vec>, zb: Double, color: Color, light: Vec, gloss: Float = 0f) {
    val n = a.size
    val pa = a.map { cam.at(it, za) ?: return }
    val pb = b.map { cam.at(it, zb) ?: return }
    // An undercoat, so the hairline joins between strips never show the floor through.
    val under = shade(color, 0.8f)
    drawPath(toPath(pa, true), under)
    drawPath(toPath(pb, true), under)
    val order = (0 until n).sortedByDescending { cam.depth((a[it] + a[(it + 1) % n]) * 0.5, za) }
    for (i in order) {
        val j = (i + 1) % n
        val e = a[j] - a[i]
        val len = e.len() + 1e-9
        val facing = ((e.y * light.x - e.x * light.y) / len).toFloat()
        val lit = max(0f, facing)
        val k = 0.90f + 0.10f * facing
        val tone = if (gloss > 0f) mix(shade(color, k), Color.White, gloss * lit * lit * lit * 0.55f) else shade(color, k)
        drawPath(toPath(listOf(pa[i], pa[j], pb[j], pb[i]), true), tone)
    }
}

/** A flat top, a touch lighter than the sides beneath it. */
private fun DrawScope.lid(cam: Cam, shape: List<Vec>, z: Double, color: Color) {
    val points = shape.map { cam.at(it, z) ?: return }
    drawPath(toPath(points, true), shade(color, 1.03f))
}

// ---------------------------------------------------------------------- paths

/**
 * Where the car is about to go: one quiet ribbon as wide as the car, a fine line down each edge
 * of it, and a bar where it has to stop. Nothing else is drawn on it.
 */
private fun DrawScope.corridor(cam: Cam, spec: VehicleSpec, planned: List<Pose>, car: Pose) {
    if (planned.size < 2) return
    // Only what is still ahead: the part already driven belongs to the tyre prints.
    val nearest = planned.indices.minByOrNull { (planned[it].pos - car.pos).len() } ?: 0
    val path = planned.subList(nearest, planned.size)
    if (path.size < 2) return
    val half = spec.width / 2 - 0.06
    val left = path.map { it.toWorld(Vec(0.0, half)) }
    val right = path.map { it.toWorld(Vec(0.0, -half)) }
    flat(cam, left + right.reversed(), 0.0, Guide.copy(alpha = if (Palette.light) 0.13f else 0.16f))
    stripe(cam, left, Guide.copy(alpha = 0.9f), 0.045f)
    stripe(cam, right, Guide.copy(alpha = 0.9f), 0.045f)
    stripe(cam, listOf(left.last(), right.last()), Guide, 0.09f)
}

private fun wrapped(a: Double): Double = com.parkcoach.core.wrap(a)

/**
 * Where the car has been: the prints of its rear tyres as two fine grey lines that fade with age.
 * An earlier drive, kept for comparison, is one fainter line down its middle.
 */
private fun DrawScope.tracks(cam: Cam, spec: VehicleSpec, samples: List<Sample>, faint: Boolean) {
    if (samples.size < 2) return
    if (faint) {
        stripe(cam, samples.map { it.pose.toWorld(Vec(spec.centerAhead, 0.0)) }, Ink.copy(alpha = 0.13f), 0.05f)
        return
    }
    val half = spec.width / 2 - 0.16
    val total = samples.size
    var start = 0
    while (start < total - 1) {
        val end = min(start + 10, total - 1)
        val piece = samples.subList(start, end + 1)
        val age = 0.25f + 0.75f * end / total
        for (side in listOf(half, -half)) {
            stripe(cam, piece.map { it.pose.toWorld(Vec(0.0, side)) }, Ink.copy(alpha = 0.22f * age), 0.07f)
        }
        start = end
    }
}

// ---------------------------------------------------------------------- cars and walls

/**
 * What a car is made of. A [rich] car is glossy and finely modelled: the driver's own. The rest are
 * matte clay in one colour, like scenery, so the eye goes to the one that matters.
 */
private class Paintwork(
    val body: Color,
    val glass: Color,
    val roof: Color,
    val tyre: Color,
    val rim: Color,
    val rich: Boolean,
    val tall: Boolean,
    /** The set of ready-made pictures to show this car with, when there is one. */
    val picture: String? = null,
    val filter: ColorFilter? = null,
)

private const val BODY = 0
private const val GLASS = 1
private const val ROOF = 2

/** Half a cross-section is drawn through this many points, sill to centre line. */
private const val KNOTS = 9

/** The shoulder, where paint meets glass, and the roof rail, where glass meets roof. */
private const val SHOULDER = 4.0
private const val RAIL = 6.0

private val Dusk = ColorFilter.tint(Color(0xFF6C6F76), BlendMode.Modulate)
private val Struck = ColorFilter.tint(Color(0x99FF453A), BlendMode.SrcAtop)

private val SHADOW = listOf(1.26 to 0.028f, 1.19 to 0.038f, 1.13 to 0.05f, 1.08 to 0.065f, 1.03 to 0.085f)

/**
 * A car body as a grid of points: stations from nose to tail, and at each station a ring that runs
 * from the left sill, up the door, over the roof and down to the right sill. Every patch between
 * four neighbouring points knows what it is made of and which way it faces.
 */
internal class Mesh(
    val ring: Int,
    val x: DoubleArray,
    val y: DoubleArray,
    val z: DoubleArray,
    val kind: IntArray,
    val nx: DoubleArray,
    val ny: DoubleArray,
    val nz: DoubleArray,
    /** Which way the surface faces at each point, averaged from the patches around it. */
    val px: DoubleArray,
    val py: DoubleArray,
    val pz: DoubleArray,
    /** The point of each ring that lies on the shoulder line. */
    val belt: Int,
    val cowlAhead: Double,
    val cowlHeight: Double,
    private val ahead: DoubleArray,
    private val wide: DoubleArray,
) {
    /** Half the width of the body at a place along the car. */
    fun halfAt(x: Double): Double {
        var nearest = 0
        for (i in ahead.indices) if (abs(ahead[i] - x) < abs(ahead[nearest] - x)) nearest = i
        return wide[nearest]
    }
}

private val meshes = HashMap<List<Double>, Mesh>()

private fun smooth(a: Double, b: Double, u: Double): Double {
    val t = ((u - a) / (b - a)).coerceIn(0.0, 1.0)
    return t * t * (3 - 2 * t)
}

/** A smooth curve through evenly spaced values, read at [t]. */
private fun through(p: DoubleArray, t: Double): Double {
    val last = p.size - 1
    val i = min(last - 1, max(0, t.toInt()))
    val f = t - i
    val p0 = p[max(0, i - 1)]
    val p1 = p[i]
    val p2 = p[i + 1]
    val p3 = p[min(last, i + 2)]
    return 0.5 * (2 * p1 + (p2 - p0) * f + (2 * p0 - 5 * p1 + 4 * p2 - p3) * f * f + (3 * p1 - p0 - 3 * p2 + p3) * f * f * f)
}

/**
 * Shapes the body from a few curves along its length: how wide it is, how high the bonnet and boot
 * are, and how far the cabin rises above them. Every cross-section is one rounded line with no
 * corners: tucked in at the sill, fullest at the door, leaning in over the glass and arched across
 * the roof. [tall] gives the upright tail and high roof of an SUV; otherwise the roof falls away
 * into a boot like a saloon. [half] is how many points make half a cross-section.
 */
internal fun meshFor(l: Double, w: Double, tall: Boolean, n: Int, half: Int): Mesh =
    meshes.getOrPut(listOf(l, w, if (tall) 1.0 else 0.0, n.toDouble(), half.toDouble())) {
        val ring = 2 * half - 1
        val nose = if (tall) 0.80 else 0.66
        val cowl = if (tall) 1.07 else 0.96
        val roof = if (tall) 1.66 else 1.43
        val tail = if (tall) 1.12 else 0.99
        val screen0 = if (tall) 0.25 else 0.27
        val screen1 = if (tall) 0.39 else 0.44
        val rear0 = if (tall) 0.85 else 0.64
        val rear1 = if (tall) 0.965 else 0.90
        val peak = if (tall) 0.56 else 0.50
        val x = DoubleArray(n * ring)
        val y = DoubleArray(n * ring)
        val z = DoubleArray(n * ring)
        val cabin = DoubleArray(n)
        val ahead = DoubleArray(n)
        val wide = DoubleArray(n)
        // Stations crowd toward the ends, where the shape curves most. Four of them are moved to sit
        // exactly where glass begins and ends, so every window has a clean straight edge.
        val along = DoubleArray(n) { 0.006 + 0.988 * (0.5 - 0.5 * cos(PI * it / (n - 1))) }
        for (edge in doubleArrayOf(screen0, screen1, rear0, rear1)) {
            var nearest = 1
            for (i in 1 until n - 1) if (abs(along[i] - edge) < abs(along[nearest] - edge)) nearest = i
            along[nearest] = edge
        }
        along.sort()
        val ys = DoubleArray(KNOTS)
        val zs = DoubleArray(KNOTS)
        for (i in 0 until n) {
            val u = along[i]
            val s = abs(2 * u - 1)
            val halfWidth = w / 2 * (1 - s.pow(4.2)).pow(1 / 4.2) * (1 - (if (u < 0.5) 0.14 else 0.09) * s * s)
            var belt = if (u <= screen0) nose + (cowl - nose) * smooth(0.0, screen0, u) else cowl + (tail - cowl) * smooth(screen1, rear1, u)
            belt *= 1 - 0.22 * s.pow(7.0)
            val g = smooth(screen0, screen1, u) * (1 - smooth(rear0, rear1, u))
            cabin[i] = g
            ahead[i] = l / 2 - u * l
            wide[i] = halfWidth
            // The roof is a long arch, highest over the front seats.
            val crown = roof - 0.06 * min(1.6, ((u - peak) / 0.22).pow(2.0))
            val rise = crown - belt
            // The underside sweeps up at both ends, which rounds the nose and tail seen from the side.
            val sill = 0.17 + 0.22 * s.pow(6.0)
            val side = belt - sill
            fun blend(a: Double, b: Double) = a + (b - a) * g
            ys[0] = 0.88
            ys[1] = 0.985
            ys[2] = 1.0
            ys[3] = 0.985
            ys[4] = 0.945
            ys[5] = blend(0.83, 0.865)
            ys[6] = blend(0.60, 0.715)
            ys[7] = blend(0.32, 0.50)
            ys[8] = 0.0
            zs[0] = sill
            zs[1] = sill + side * 0.25
            zs[2] = sill + side * 0.55
            zs[3] = sill + side * 0.85
            zs[4] = sill + side * 0.985
            zs[5] = blend(belt + 0.022, belt + rise * 0.30)
            zs[6] = blend(belt + 0.047, belt + rise * 0.84)
            zs[7] = blend(belt + 0.060, belt + rise * 0.975)
            zs[8] = blend(belt + 0.064, crown + 0.012)
            for (j in 0 until ring) {
                val h = if (j < half) j else ring - 1 - j
                val t = h * (KNOTS - 1.0) / (half - 1)
                val at = i * ring + j
                val out = if (h == half - 1) 0.0 else through(ys, t) * halfWidth
                x[at] = ahead[i]
                y[at] = if (j < half) out else -out
                z[at] = through(zs, t)
            }
        }
        val across = ring - 1
        val count = (n - 1) * across
        val kind = IntArray(count)
        val nx = DoubleArray(count)
        val ny = DoubleArray(count)
        val nz = DoubleArray(count)
        for (i in 0 until n - 1) {
            for (k in 0 until across) {
                val q = i * across + k
                val a = i * ring + k
                val b = a + 1
                val d = a + ring
                // Outward normal: along the body crossed with around the ring.
                val ux = x[d] - x[a]
                val uy = y[d] - y[a]
                val uz = z[d] - z[a]
                val vx = x[b] - x[a]
                val vy = y[b] - y[a]
                val vz = z[b] - z[a]
                val cx = uy * vz - uz * vy
                val cy = uz * vx - ux * vz
                val cz = ux * vy - uy * vx
                val len = Math.sqrt(cx * cx + cy * cy + cz * cz) + 1e-12
                nx[q] = cx / len
                ny[q] = cy / len
                nz[q] = cz / len
                // Which strip of the half ring this is, counted up from the sill.
                val strip = min(k, across - 1 - k)
                val mid = (strip + 0.5) * (KNOTS - 1.0) / (half - 1)
                val g = (cabin[i] + cabin[i + 1]) / 2
                kind[q] = when {
                    mid < SHOULDER || g < 0.01 -> BODY
                    mid < RAIL -> GLASS
                    g > 0.99 -> ROOF
                    else -> GLASS
                }
            }
        }
        // Neighbouring patches share their facing, twice over, so the whole body reads as one
        // continuous skin and no patch catches the light on its own.
        repeat(2) {
            val sx = nx.copyOf()
            val sy = ny.copyOf()
            val sz = nz.copyOf()
            for (q in 0 until count) {
                var ax = sx[q] * 2
                var ay = sy[q] * 2
                var az = sz[q] * 2
                val i = q / across
                val k = q % across
                for (other in intArrayOf(
                    if (k > 0) q - 1 else -1, if (k < across - 1) q + 1 else -1,
                    if (i > 0) q - across else -1, if (i < n - 2) q + across else -1,
                )) {
                    if (other < 0) continue
                    ax += sx[other]
                    ay += sy[other]
                    az += sz[other]
                }
                val len = Math.sqrt(ax * ax + ay * ay + az * az) + 1e-12
                nx[q] = ax / len
                ny[q] = ay / len
                nz[q] = az / len
            }
        }
        val px = DoubleArray(n * ring)
        val py = DoubleArray(n * ring)
        val pz = DoubleArray(n * ring)
        for (i in 0 until n) {
            for (j in 0 until ring) {
                var ax = 0.0
                var ay = 0.0
                var az = 0.0
                for (di in intArrayOf(i - 1, i)) {
                    for (dk in intArrayOf(j - 1, j)) {
                        if (di < 0 || di > n - 2 || dk < 0 || dk > across - 1) continue
                        val q = di * across + dk
                        ax += nx[q]
                        ay += ny[q]
                        az += nz[q]
                    }
                }
                val len = Math.sqrt(ax * ax + ay * ay + az * az) + 1e-12
                px[i * ring + j] = ax / len
                py[i * ring + j] = ay / len
                pz[i * ring + j] = az / len
            }
        }
        val belt = (SHOULDER * (half - 1) / (KNOTS - 1)).toInt()
        Mesh(ring, x, y, z, kind, nx, ny, nz, px, py, pz, belt, l / 2 - (screen0 + 0.035) * l, cowl, ahead, wide)
    }

private fun lit(c: Color, k: Double, white: Double): Color = Color(
    (c.red * k + white).toFloat().coerceIn(0f, 1f),
    (c.green * k + white).toFloat().coerceIn(0f, 1f),
    (c.blue * k + white).toFloat().coerceIn(0f, 1f),
    1f,
)

/** A wheel seen from the side: a filled circle standing upright on the ground, turned by [turn]. */
private fun DrawScope.disc(cam: Cam, f: Pose, ahead: Double, out: Double, turn: Double, height: Double, r: Double, color: Color) {
    val path = Path()
    for (i in 0 until 20) {
        val a = 2 * PI * i / 20
        val along = r * cos(a)
        val p = f.toWorld(Vec(ahead + along * cos(turn), out + along * sin(turn)))
        val o = cam.at(p, max(0.0, height + r * sin(a))) ?: return
        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
    }
    path.close()
    drawPath(path, color)
}

/**
 * Shows a car from its ready-made pictures, soft shadow included. Returns false when there are none
 * for [set], and the car is then built from patches instead.
 */
private fun DrawScope.portrait(cam: Cam, set: String, box: Box, haze: Float, filter: ColorFilter?): Boolean {
    val (long, wide) = Sprites.size(set) ?: return false
    val base = cam.at(box.c) ?: return false
    val unit = (Sprites.CELL / Sprites.SIZE).toFloat()
    val alpha = 1f - 0.5f * haze
    val corner = Offset(-Sprites.SIZE / 2f, -Sprites.SIZE / 2f)
    if (Math.toDegrees(cam.pitch) >= 80.0) {
        // From overhead one picture serves every heading: it is turned to point the way the car does.
        val sheet = Sprites.sheet("${set}_top") ?: return false
        val ahead = Vec(cos(box.h), sin(box.h))
        val nose = cam.at(box.c + ahead) ?: return false
        val flank = cam.at(box.c + Vec(-ahead.y, ahead.x)) ?: return false
        val fx = nose.x - base.x
        val fy = nose.y - base.y
        val turn = Math.toDegrees(atan2(fx.toDouble(), -fy.toDouble())).toFloat()
        val across = hypot(flank.x - base.x, flank.y - base.y) * unit * (box.width / wide).toFloat()
        val along = hypot(fx, fy) * unit * (box.length / long).toFloat()
        withTransform({
            translate(base.x, base.y)
            rotate(turn, Offset.Zero)
            scale(across, along, Offset.Zero)
        }) {
            drawImage(sheet, topLeft = corner, alpha = alpha, colorFilter = filter)
        }
        return true
    }
    // From lower down, the nearest of the pictures taken all round the car, stood upright where it is.
    val dx = cam.cx - box.c.x
    val dy = cam.cy - box.c.y
    val flat = hypot(dx, dy)
    if (flat < 1e-6) return false
    val rise = atan2(cam.cz - 0.6, flat)
    val level = Sprites.HEIGHTS.minByOrNull { abs(it - Math.toDegrees(rise)) } ?: return false
    val sheet = Sprites.sheet("${set}_$level") ?: return false
    val step = 2 * PI / Sprites.TURNS
    val toViewer = wrapped(atan2(dy, dx) - box.h)
    val nearest = Math.round(toViewer / step).toInt()
    val rest = toViewer - nearest * step
    val index = ((nearest % Sprites.TURNS) + Sprites.TURNS) % Sprites.TURNS
    // The picture's upright is the world's upright seen from the viewer's side; find where that points here.
    val away = Vec(-dx / flat, -dy / flat)
    val tip = cam.at(box.c + away * sin(rise), cos(rise)) ?: return false
    val ux = tip.x - base.x
    val uy = tip.y - base.y
    // Between two pictures, the nearer one is turned a little to make up the difference.
    val turn = (Math.toDegrees(atan2(ux.toDouble(), -uy.toDouble())) + Math.toDegrees(rest) * sin(rise)).toFloat()
    val k = hypot(ux, uy) * unit * (box.length / long).toFloat()
    val cell = IntSize(Sprites.SIZE, Sprites.SIZE)
    withTransform({
        translate(base.x, base.y)
        rotate(turn, Offset.Zero)
        scale(k, k, Offset.Zero)
    }) {
        drawImage(
            sheet,
            srcOffset = IntOffset(index % Sprites.COLUMNS * Sprites.SIZE, index / Sprites.COLUMNS * Sprites.SIZE),
            srcSize = cell,
            dstOffset = IntOffset(-Sprites.SIZE / 2, -Sprites.SIZE / 2),
            dstSize = cell,
            alpha = alpha,
            colorFilter = filter,
        )
    }
    return true
}

/**
 * Draws one car: a soft shadow, the tyres, then every body patch that faces the viewer, farthest
 * first, and last the wheels on the side that can be seen. Clay is lit evenly and never shines;
 * the driver's car gets a soft highlight on its paint and a sharp one on its glass.
 */
private fun DrawScope.car(cam: Cam, box: Box, paint: Paintwork, frontAxle: Double, rearAxle: Double, steer: Double, light: Vec) {
    val f = Pose(box.c.x, box.c.y, box.h)
    val l = box.length
    val w = box.width
    val haze = cam.haze(box.c)
    if (paint.picture != null && portrait(cam, paint.picture, box, haze, paint.filter)) return
    val air = Floor
    fun aired(c: Color): Color = if (haze > 0f) mix(c, air, haze) else c

    // Thin layers, each a little larger and fainter, read as one soft edge.
    val away = light * -0.10
    val weight = (if (Palette.light) 0.5f else 1f) * (1 - haze)
    for ((grow, alpha) in SHADOW) {
        flat(cam, place(f, hull(l * grow, w * grow, 4.2, 24, 0.12, 0.08)).map { it + away }, 0.0, Color.Black.copy(alpha = alpha * weight))
    }

    val n = if (paint.rich) 40 else 22
    val mesh = meshFor(l, w, paint.tall, n, if (paint.rich) 13 else 9)
    val ring = mesh.ring
    val across = ring - 1
    val wheels = listOf(frontAxle to steer, rearAxle to 0.0)
    val tyre = aired(paint.tyre)
    val tread = hull(0.66, 0.23, 5.0, 12, 0.0, 0.0)
    for ((ahead, turn) in wheels) {
        val track = mesh.halfAt(ahead) - 0.14
        for (side in listOf(track, -track)) {
            val centre = f.toWorld(Vec(ahead, side))
            val print = place(Pose(centre.x, centre.y, f.h + turn), tread)
            loft(cam, print, 0.0, print, 0.62, tyre, light)
        }
    }

    val points = mesh.x.size
    val c = cos(f.h)
    val s = sin(f.h)
    val wx = DoubleArray(points)
    val wy = DoubleArray(points)
    val sx = FloatArray(points)
    val sy = FloatArray(points)
    for (i in 0 until points) {
        wx[i] = f.x + mesh.x[i] * c - mesh.y[i] * s
        wy[i] = f.y + mesh.x[i] * s + mesh.y[i] * c
        if (!cam.into(wx[i], wy[i], mesh.z[i], sx, sy, i)) return
    }
    // The lamp sits high, so tops are bright and flanks fall away.
    val lx = light.x * 0.42
    val ly = light.y * 0.42
    val lz = 0.907
    // One line of sight for the whole car is close enough, and saves a square root per point.
    var ex = cam.cx - f.x
    var ey = cam.cy - f.y
    var ez = cam.cz - 0.8
    val el = Math.sqrt(ex * ex + ey * ey + ez * ez)
    ex /= el
    ey /= el
    ez /= el
    var hx = lx + ex
    var hy = ly + ey
    var hz = lz + ez
    val hl = Math.sqrt(hx * hx + hy * hy + hz * hz)
    hx /= hl
    hy /= hl
    hz /= hl
    // Light is worked out at every point and blended across each patch, so panels curve
    // smoothly from one to the next and no flat facets show.
    val bright = DoubleArray(points)
    val shine = DoubleArray(points)
    for (i in 0 until points) {
        val nx = mesh.px[i] * c - mesh.py[i] * s
        val ny = mesh.px[i] * s + mesh.py[i] * c
        val nz = mesh.pz[i]
        bright[i] = max(0.0, nx * lx + ny * ly + nz * lz)
        shine[i] = max(0.0, nx * hx + ny * hy + nz * hz)
    }
    val body = aired(paint.body)
    val glass = aired(paint.glass)
    val roof = aired(paint.roof)
    fun skin(kind: Int, d: Double, sh: Double, facing: Double, height: Double): Color {
        if (!paint.rich) {
            // Clay: mostly its own colour, a little brighter on top and a little darker near the ground.
            val k = (0.87 + 0.13 * d) * (0.92 + 0.08 * smooth(0.2, 0.8, height))
            return when (kind) {
                BODY -> lit(body, k, 0.0)
                GLASS -> lit(glass, k, sh.pow(18.0) * 0.05)
                else -> lit(roof, k, 0.0)
            }
        }
        return when (kind) {
            BODY -> lit(body, 0.46 + 0.54 * d, sh.pow(10.0) * 0.17 + (1 - facing).pow(4.0) * 0.07)
            GLASS -> lit(glass, 0.60 + 0.40 * d, sh.pow(16.0) * 0.20 + (1 - facing).pow(3.0) * 0.12)
            else -> lit(roof, 0.55 + 0.45 * d, sh.pow(14.0) * 0.18 + (1 - facing).pow(4.0) * 0.05)
        }
    }
    val quads = mesh.kind.size
    val depth = DoubleArray(quads)
    val facingOf = DoubleArray(quads)
    val order = ArrayList<Int>(quads)
    for (q in 0 until quads) {
        val a = q / across * ring + q % across
        val e = a + ring + 1
        val mx = (wx[a] + wx[e]) / 2
        val my = (wy[a] + wy[e]) / 2
        val mz = (mesh.z[a] + mesh.z[e]) / 2
        val nx = mesh.nx[q] * c - mesh.ny[q] * s
        val ny = mesh.nx[q] * s + mesh.ny[q] * c
        var vx = cam.cx - mx
        var vy = cam.cy - my
        var vz = cam.cz - mz
        val vl = Math.sqrt(vx * vx + vy * vy + vz * vz)
        vx /= vl
        vy /= vl
        vz /= vl
        val facing = nx * vx + ny * vy + mesh.nz[q] * vz
        if (facing <= 0.0) continue
        facingOf[q] = facing
        depth[q] = cam.depthOf(mx, my, mz)
        order.add(q)
    }
    order.sortByDescending { depth[it] }
    for (q in order) {
        val a = q / across * ring + q % across
        val b = a + 1
        val d = a + ring
        val e = d + 1
        // Each patch is drawn a hair oversize so no seams show between neighbours.
        val mx = (sx[a] + sx[b] + sx[d] + sx[e]) / 4
        val my = (sy[a] + sy[b] + sy[d] + sy[e]) / 4
        val path = Path()
        var first = true
        for (i in intArrayOf(a, b, e, d)) {
            val dx = sx[i] - mx
            val dy = sy[i] - my
            val len = hypot(dx, dy) + 1e-3f
            val px = sx[i] + dx / len * 0.7f
            val py = sy[i] + dy / len * 0.7f
            if (first) path.moveTo(px, py) else path.lineTo(px, py)
            first = false
        }
        path.close()
        val kind = mesh.kind[q]
        // The light changes across a patch either around the ring or along the car. It is blended
        // in whichever direction it changes more, so bonnets and roofs shade as smoothly as flanks.
        val round = abs(bright[a] + bright[d] - bright[b] - bright[e]) + abs(shine[a] + shine[d] - shine[b] - shine[e])
        val lengthways = abs(bright[a] + bright[b] - bright[d] - bright[e]) + abs(shine[a] + shine[b] - shine[d] - shine[e])
        val p0: Int
        val p1: Int
        val q0: Int
        val q1: Int
        if (round >= lengthways) {
            p0 = a; p1 = d; q0 = b; q1 = e
        } else {
            p0 = a; p1 = b; q0 = d; q1 = e
        }
        val from = skin(kind, (bright[p0] + bright[p1]) / 2, (shine[p0] + shine[p1]) / 2, facingOf[q], (mesh.z[p0] + mesh.z[p1]) / 2)
        val to = skin(kind, (bright[q0] + bright[q1]) / 2, (shine[q0] + shine[q1]) / 2, facingOf[q], (mesh.z[q0] + mesh.z[q1]) / 2)
        val start = Offset((sx[p0] + sx[p1]) / 2, (sy[p0] + sy[p1]) / 2)
        val end = Offset((sx[q0] + sx[q1]) / 2, (sy[q0] + sy[q1]) / 2)
        if (hypot(end.x - start.x, end.y - start.y) < 1.5f) {
            drawPath(path, from)
        } else {
            drawPath(path, Brush.linearGradient(listOf(from, to), start = start, end = end))
        }
    }
    // Close whichever end faces the viewer.
    for ((station, ahead) in listOf(0 to 1.0, n - 1 to -1.0)) {
        val base = station * ring
        val towards = (c * ahead) * (cam.cx - wx[base + ring / 2]) + (s * ahead) * (cam.cy - wy[base + ring / 2])
        if (towards <= 0) continue
        val path = Path()
        for (j in 0 until ring) if (j == 0) path.moveTo(sx[base], sy[base]) else path.lineTo(sx[base + j], sy[base + j])
        path.close()
        val k = if (paint.rich) 0.40 + 0.35 * max(0.0, c * ahead * lx + s * ahead * ly) else 0.80
        drawPath(path, lit(body, k, 0.0))
    }

    // The wheels on the side the viewer can see. From high above the body hides them, so they are
    // drawn only when the view is low enough to look under its widest line.
    val toViewer = (cam.cx - f.x) * -s + (cam.cy - f.y) * c
    val low = abs(toViewer) / max(0.1, cam.cz - 0.33)
    if (low > 0.45) {
        val near = if (toViewer > 0) 1 else -1
        val well = mix(body, Color.Black, if (paint.rich) 0.55f else 0.20f)
        val rim = aired(paint.rim)
        for ((ahead, turn) in wheels) {
            val out = near * (mesh.halfAt(ahead) + 0.012)
            disc(cam, f, ahead, out, turn, 0.335, 0.40, well)
            disc(cam, f, ahead, out, turn, 0.335, 0.335, tyre)
            disc(cam, f, ahead, out, turn, 0.335, 0.215, rim)
            disc(cam, f, ahead, out, turn, 0.335, 0.07, mix(rim, Color.Black, 0.35f))
        }
    }
    if (!paint.rich) return

    // Door mirrors.
    for (side in listOf(1, -1)) {
        val reach = mesh.halfAt(mesh.cowlAhead)
        val mirror = listOf(
            Vec(mesh.cowlAhead + 0.05, side * reach * 0.94), Vec(mesh.cowlAhead + 0.03, side * (reach + 0.17)),
            Vec(mesh.cowlAhead - 0.10, side * (reach + 0.19)), Vec(mesh.cowlAhead - 0.12, side * reach * 0.94),
        ).map { f.toWorld(it) }
        flat(cam, mirror, mesh.cowlHeight + 0.04, shade(body, 0.88f))
    }
    // Lamps wrap the corners: white at the nose, red at the tail with a thin bar between.
    val noseZ = mesh.z[2 * ring + mesh.belt] - 0.03
    val tailZ = mesh.z[(n - 3) * ring + mesh.belt] - 0.03
    for (side in listOf(1, -1)) {
        val head = listOf(Vec(l / 2 - 0.13, side * w * 0.20), Vec(l / 2 - 0.24, side * w * 0.34), Vec(l / 2 - 0.42, side * w * 0.41)).map { f.toWorld(it) }
        glow(cam, head, Color.White, 0.06f, z = noseZ)
        val rear = listOf(Vec(-l / 2 + 0.11, side * w * 0.18), Vec(-l / 2 + 0.19, side * w * 0.34), Vec(-l / 2 + 0.35, side * w * 0.42)).map { f.toWorld(it) }
        glow(cam, rear, Brake, 0.06f, z = tailZ)
    }
    val bar = listOf(Vec(-l / 2 + 0.11, w * 0.18), Vec(-l / 2 + 0.11, -w * 0.18)).map { f.toWorld(it) }
    stripe(cam, bar, Brake.copy(alpha = 0.8f), 0.035f, z = tailZ)
}

/** The soft shadow at the foot of a wall or kerb. */
private fun DrawScope.footing(cam: Cam, box: Box) {
    val weight = if (Palette.light) 0.5f else 1f
    for ((grow, alpha) in listOf(0.32 to 0.035f, 0.20 to 0.05f, 0.09 to 0.08f)) {
        flat(cam, box.grow(grow).corners(), 0.0, Color.Black.copy(alpha = alpha * weight))
    }
}

/** A wall or kerb cut across into lengths of a couple of metres, each a hair over so no gap shows between them. */
private fun lengths(box: Box): List<Box> {
    val along = box.length >= box.width
    val long = if (along) box.length else box.width
    val n = max(1, Math.ceil(long / 2.2).toInt())
    if (n == 1) return listOf(box)
    val f = Pose(box.c.x, box.c.y, box.h)
    val each = long / n + 0.02
    return List(n) { i ->
        val at = ((i + 0.5) / n - 0.5) * long
        if (along) Box(f.toWorld(Vec(at, 0.0)), box.h, each, box.width) else Box(f.toWorld(Vec(0.0, at)), box.h, box.length, each)
    }
}

/**
 * One length of a wall or kerb, in the same tone as the rest of its wall. Only the faces on the
 * outside of the whole wall are drawn: where one length meets the next there is nothing, so the
 * wall reads as one piece. [along] says the wall was cut across its length; [first] and [last]
 * say whether this is an end of it.
 */
private fun DrawScope.block(cam: Cam, box: Box, kind: Kind, struck: Boolean, fade: Float, light: Vec, along: Boolean = true, first: Boolean = true, last: Boolean = true) {
    val height = if (kind == Kind.KERB) 0.16 else 1.8
    val color = if (struck) Brake else if (kind == Kind.KERB) shade(Concrete, 0.94f) else Concrete
    val tone = mix(color, Floor, fade)
    val side = shade(tone, 0.92f)
    // Front-left, front-right, rear-right, rear-left: so side 0 is the front end, 1 the right
    // flank, 2 the rear end and 3 the left flank.
    val base = box.corners()
    val foot = base.map { cam.at(it, 0.0) ?: return }
    val top = base.map { cam.at(it, height) ?: return }
    val faces = (0..3).filter { i ->
        when {
            along && i == 0 -> last
            along && i == 2 -> first
            !along && i == 3 -> last
            !along && i == 1 -> first
            else -> true
        }
    }
    for (i in faces.sortedByDescending { cam.depth((base[it] + base[(it + 1) % 4]) * 0.5) }) {
        val j = (i + 1) % 4
        val e = base[j] - base[i]
        val facing = ((e.y * light.x - e.x * light.y) / (e.len() + 1e-9)).toFloat()
        drawPath(toPath(listOf(foot[i], foot[j], top[j], top[i]), true), shade(side, 0.90f + 0.10f * facing))
    }
    drawPath(toPath(top, true), shade(tone, 1.03f))
}

/** A real traffic cone the driver has put down: the one orange thing in the picture, with a white band. */
private fun DrawScope.cone(cam: Cam, at: Vec, struck: Boolean, light: Vec) {
    fun ring(radius: Double) = List(14) { Vec(at.x + radius * cos(2 * PI * it / 14), at.y + radius * sin(2 * PI * it / 14)) }
    val body = if (struck) Brake else Amber
    flat(cam, ring(0.26), 0.0, Color.Black.copy(alpha = 0.10f))
    flat(cam, ring(0.20), 0.0, shade(body, 0.86f))
    loft(cam, ring(0.14), 0.02, ring(0.085), 0.28, body, light)
    loft(cam, ring(0.085), 0.28, ring(0.065), 0.40, Color.White, light)
    loft(cam, ring(0.065), 0.40, ring(0.03), 0.62, body, light)
    flat(cam, ring(0.03), 0.62, shade(body, 1.05f))
}

private fun DrawScope.obstacle(cam: Cam, o: Obstacle, struck: Boolean, light: Vec) {
    when (o.kind) {
        Kind.CAR -> {
            // One clay for the whole row, a touch lighter or darker car to car, some saloons, some SUVs.
            val seed = abs((o.box.c.x * 7.3 + o.box.c.y * 3.1) % 1.0)
            val body = if (struck) Brake else shade(Clay, 0.97f + 0.05f * seed.toFloat())
            // A row of Hyundais. The size a car was given picks the model: a Casper for a small
            // one, a Staria for a big one. Ordinary cars are a mix, and now and then one of them is
            // shown as a Casper too, at its own size inside the room an ordinary car takes.
            val picture: String
            var shown = o.box
            when {
                o.box.length < 4.0 -> picture = "casper_clay"
                o.box.length > 4.9 -> picture = "staria_clay"
                seed < 0.18 -> {
                    picture = "casper_clay"
                    shown = Box(o.box.c, o.box.h, 3.6, 1.6)
                }
                seed < 0.6 -> picture = "elantra_clay"
                else -> picture = "ioniq_clay"
            }
            val paint = Paintwork(
                body = body,
                glass = mix(body, Color.Black, if (Palette.light) 0.065f else 0.16f),
                roof = body,
                tyre = mix(body, Color.Black, if (Palette.light) 0.24f else 0.40f),
                rim = mix(body, Color.White, 0.16f),
                rich = false,
                tall = seed > 0.5,
                picture = picture,
                // The pictures are pale clay; by night they are dimmed, and a struck car turns red.
                filter = if (struck) Struck else if (Palette.light) null else Dusk,
            )
            car(cam, shown, paint, shown.length * 0.30, -shown.length * 0.30, 0.0, light)
        }
        Kind.CONE -> cone(cam, o.box.c, struck, light)
        else -> block(cam, o.box, o.kind, struck, cam.haze(o.box.c), light)
    }
}

/** The driver's own car: the one glossy thing in the picture, with front wheels that turn with the real steering wheel. */
private fun DrawScope.ego(cam: Cam, data: SceneData, light: Vec) {
    val spec = data.spec
    val box = spec.body(data.pose)
    val tall = spec.id.contains("suv") || spec.id == "van"
    val paint = Paintwork(
        if (data.hit != null) Brake else Pearl, GlassDark, GlassRoof, Tyre, Rim, rich = true, tall = tall,
        picture = "ioniq",
        filter = if (data.hit != null) Struck else null,
    )
    car(cam, box, paint, spec.wheelbase - spec.centerAhead, -spec.centerAhead, spec.road(data.wheelDeg), light)
}

/**
 * Parking-sensor arcs at each corner of the car: one green arc when something is within reach,
 * two amber as it closes, three red when it is nearly touching.
 */
private fun DrawScope.radar(cam: Cam, lot: Lot, body: Box) {
    val f = Pose(body.c.x, body.c.y, body.h)
    val l = body.length / 2
    val w = body.width / 2
    for ((ahead, side) in listOf(l to w, l to -w, -l to w, -l to -w)) {
        val corner = f.toWorld(Vec(ahead * 0.97, side * 0.94))
        val gap = lot.gap(corner)
        if (gap > 1.2) continue
        val color = if (gap < 0.35) Brake else if (gap < 0.7) Color(0xFFFFD60A) else Guide
        val lit = if (gap < 0.35) 3 else if (gap < 0.7) 2 else 1
        val out = Math.atan2(side, ahead) + body.h
        for (ring in 0 until lit) {
            val r = 0.80 - 0.26 * ring
            val arc = (-4..4).map { corner + Vec(cos(out + it * 0.17), sin(out + it * 0.17)) * r }
            glow(cam, arc, color, 0.07f, z = 0.02)
        }
    }
}
