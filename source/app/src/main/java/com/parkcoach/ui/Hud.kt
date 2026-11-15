package com.parkcoach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parkcoach.core.Cue
import com.parkcoach.core.DriveStats
import com.parkcoach.core.Engine
import com.parkcoach.core.Gear
import com.parkcoach.core.Hint
import com.parkcoach.core.Phase
import com.parkcoach.core.Pose
import com.parkcoach.core.SlotType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** A small on/off choice. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) Palette.Text else Palette.Soft, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text,
            color = if (selected) Palette.OnBright else Palette.Text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * The scene, seen from straight above. Dragging tips the view down into three-quarter perspective and
 * turns it around the lot; a double tap puts it back overhead.
 */
@Composable
fun OrbitScene(modifier: Modifier = Modifier, build: (tilt: Float, spin: Float) -> SceneData) {
    var tilt by remember { mutableStateOf(0f) }
    var spin by remember { mutableStateOf(0f) }
    SceneView(
        build(tilt, spin),
        modifier
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    spin += drag.x * 0.005f
                    tilt = (tilt - drag.y * 0.0035f).coerceIn(0f, 1f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    tilt = 0f
                    spin = 0f
                })
            },
    )
}

/**
 * The steering wheel as the car reports it, turning live: a dark rim, three spokes and a hub that
 * carries the number. A stripe marks the top of the wheel. The green arc is how much further it has
 * to go, and the green dot is where the stripe must end up.
 *
 * While the wheel is short of where it should be, arrowheads run round the rim the way it has to
 * turn and the arc breathes, so "turn more" is seen without reading anything. [beat] runs from 0
 * to 1 and round again, and moves them.
 */
@Composable
fun SteeringDial(wheelDeg: Double, targetDeg: Double?, modifier: Modifier = Modifier, beat: Float = 0f) {
    Canvas(modifier) {
        val r = size.minDimension / 2 * 0.76f
        val c = Offset(size.width / 2, size.height / 2)
        fun at(deg: Double, radius: Float): Offset {
            val a = Math.toRadians(deg)
            return Offset(c.x + radius * cos(a).toFloat(), c.y + radius * sin(a).toFloat())
        }
        val ink = Palette.Text.copy(alpha = 0.90f)
        val rim = r * 0.13f
        val hub = r * 0.58f

        /** One spoke: wide where it leaves the hub, slimmer where it meets the rim. */
        fun spoke(deg: Double, wide: Float, slim: Float) {
            val a = Math.toRadians(deg)
            val dx = cos(a).toFloat()
            val dy = sin(a).toFloat()
            val inner = hub * 0.9f
            val outer = r - rim * 0.25f
            val path = Path()
            path.moveTo(c.x + dx * inner + dy * wide, c.y + dy * inner - dx * wide)
            path.lineTo(c.x + dx * outer + dy * slim, c.y + dy * outer - dx * slim)
            path.lineTo(c.x + dx * outer - dy * slim, c.y + dy * outer + dx * slim)
            path.lineTo(c.x + dx * inner - dy * wide, c.y + dy * inner + dx * wide)
            path.close()
            drawPath(path, ink)
        }
        // Two arms that droop a little, and one straight down.
        spoke(wheelDeg + 10, r * 0.20f, r * 0.11f)
        spoke(wheelDeg + 170, r * 0.20f, r * 0.11f)
        spoke(wheelDeg + 90, r * 0.13f, r * 0.08f)
        drawCircle(ink, radius = r, center = c, style = Stroke(width = rim))
        drawCircle(Palette.Panel.copy(alpha = 0.16f), radius = r - rim * 0.16f, center = c, style = Stroke(width = rim * 0.12f))
        drawCircle(ink, radius = hub, center = c)
        drawCircle(Palette.Panel, radius = hub - rim * 0.40f, center = c)
        if (targetDeg != null) {
            val gap = targetDeg - wheelDeg
            val more = gap.coerceIn(-359.0, 359.0)
            val short = abs(gap) > TURN_MORE
            if (abs(more) > 3) {
                val swell = if (short) 0.22f + 0.12f * sin(beat * 2 * PI).toFloat() else 0.25f
                drawArc(
                    Palette.Go.copy(alpha = swell),
                    startAngle = (wheelDeg - 90).toFloat(),
                    sweepAngle = more.toFloat(),
                    useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(width = rim * 2.4f, cap = StrokeCap.Round),
                )
                drawArc(
                    Palette.Go,
                    startAngle = (wheelDeg - 90).toFloat(),
                    sweepAngle = more.toFloat(),
                    useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(width = rim * 0.55f, cap = StrokeCap.Round),
                )
            }
            if (short) {
                // Arrowheads set off from the top of the wheel, one after another, and fade as they
                // reach where it has to be.
                val way = if (gap > 0) 1.0 else -1.0
                val run = abs(gap).coerceIn(70.0, 300.0)
                val track = r + rim * 1.5f
                val reach = rim * 1.3f
                for (k in 0 until ARROWS) {
                    val f = (beat + k.toFloat() / ARROWS) % 1f
                    val a = Math.toRadians(wheelDeg - 90 + way * run * f)
                    val along = Offset((-sin(a) * way).toFloat(), (cos(a) * way).toFloat())
                    val outward = Offset(cos(a).toFloat(), sin(a).toFloat())
                    val p = Offset(c.x + track * outward.x, c.y + track * outward.y)
                    val tip = Offset(p.x + along.x * reach * 0.5f, p.y + along.y * reach * 0.5f)
                    val color = Palette.Go.copy(alpha = sin(PI * f).toFloat().coerceIn(0f, 1f))
                    for (side in listOf(-1f, 1f)) {
                        val tail = Offset(
                            p.x - along.x * reach * 0.5f + outward.x * reach * 0.55f * side,
                            p.y - along.y * reach * 0.5f + outward.y * reach * 0.55f * side,
                        )
                        drawLine(color, tail, tip, strokeWidth = rim * 0.46f, cap = StrokeCap.Round)
                    }
                }
            }
            drawCircle(Palette.Go, radius = rim * 0.85f, center = at(targetDeg - 90, r))
        }
        // The top of the wheel.
        drawLine(Palette.OnBright, at(wheelDeg - 90, r - rim * 0.34f), at(wheelDeg - 90, r + rim * 0.34f), strokeWidth = rim * 0.42f, cap = StrokeCap.Round)
    }
}

/** Degrees short of the target at which the wheel starts asking to be turned further. */
const val TURN_MORE = 20.0
private const val ARROWS = 3

/** How long the arrowheads take to go once round, in seconds. */
const val TURN_BEAT = 1.1

/** P R N D as one capsule: the selected gear is a white disc, the one being asked for a green one. */
@Composable
fun GearStrip(gear: Gear, wanted: Gear?) {
    Row(Modifier.glass(40).padding(7.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((g, label) in listOf(Gear.PARK to "P", Gear.REVERSE to "R", Gear.NEUTRAL to "N", Gear.DRIVE to "D")) {
            val on = g == gear
            val ask = g == wanted && !on
            Box(
                Modifier.size(52.dp).background(if (ask) Palette.Go else if (on) Palette.Text else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (on || ask) Palette.OnBright else Palette.Dim,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * A small word above, the instruction, and one large figure: never more than that at a time.
 * Set from the left, because it sits in the driver's column.
 */
@Composable
private fun Instruction(step: String, title: String, figure: String, tone: Color, note: String = "") {
    Column(horizontalAlignment = Alignment.Start) {
        if (step.isNotEmpty()) {
            Text(step, color = tone, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
            Spacer(Modifier.height(2.dp))
        }
        Text(title, color = Palette.Text, fontSize = 36.sp, lineHeight = 46.sp, fontWeight = FontWeight.SemiBold)
        if (figure.isNotEmpty()) {
            // A figure with a word in front of it is longer than the column is wide at full size.
            Text(figure, color = Palette.Text, fontSize = if (figure.length > 7) 60.sp else 78.sp, fontWeight = FontWeight.Light, maxLines = 1)
        }
        if (note.isNotEmpty()) Text(note, color = Palette.Watch, fontSize = 24.sp, fontWeight = FontWeight.Medium)
    }
}

private fun stepName(h: Hint): String = when (h.cue) {
    Cue.FIND_SPOT -> "자리 잡기"
    Cue.STOP -> "정지"
    Cue.STEER -> "핸들"
    Cue.SHIFT -> "기어"
    Cue.MOVE -> if (h.dir > 0) "전진" else "후진"
    Cue.PARK -> "완료"
}

/** Why the car's speed is asked for, shown before the system's own, differently worded question. */
@Composable
fun CarAsk(core: AppCore) {
    Box(
        Modifier.fillMaxSize().background(Palette.Bg.copy(alpha = 0.86f)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = 880.dp).glass(36).padding(horizontal = 40.dp, vertical = 32.dp)) {
            Text(Texts.CAR_ASK_TITLE, color = Palette.Text, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(18.dp))
            Text(Texts.CAR_ASK, color = Palette.Text, fontSize = 24.sp, lineHeight = 34.sp)
            Spacer(Modifier.height(24.dp))
            BigButton("권한 허용하기", { core.answerCarAsk(true) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            QuietButton("권한 없이 둘러보기", { core.answerCarAsk(false) })
        }
    }
}

/**
 * What the driver agrees to before the first practice after opening the app. It covers the whole
 * picture, so nothing can be started until it has been read.
 */
@Composable
private fun SafetyCheck(onAgree: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Palette.Bg.copy(alpha = 0.86f)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = 880.dp).glass(36).padding(horizontal = 40.dp, vertical = 32.dp)) {
            Text("시작하기 전에", color = Palette.Text, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(18.dp))
            for ((i, line) in Texts.CHECKS.withIndex()) {
                Row(verticalAlignment = Alignment.Top) {
                    Text("${i + 1}", color = Palette.Go, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(34.dp))
                    Text(line, color = Palette.Text, fontSize = 24.sp, lineHeight = 34.sp)
                }
                Spacer(Modifier.height(12.dp))
            }
            Spacer(Modifier.height(10.dp))
            BigButton("확인했어요", onAgree, Modifier.fillMaxWidth())
        }
    }
}

/** What the instruments are showing, worked out once: for the full screen and for the small window. */
class Readout(s: UiState, e: Engine) {
    val hint: Hint? = e.hint

    /** The gear being asked for, when one is. */
    val wanted: Gear?

    /** Where the steering wheel should be, in degrees. */
    val steerTarget: Double?

    /** Metres still to go, while the instruction is to move. */
    val left: Double?

    init {
        val h = hint
        val guided = s.assist == Assist.FULL
        wanted = when (h?.cue) {
            Cue.SHIFT -> if (h.dir > 0) Gear.DRIVE else Gear.REVERSE
            Cue.PARK -> Gear.PARK
            else -> null
        }
        steerTarget = if (guided && h != null && (h.cue == Cue.STEER || h.cue == Cue.MOVE || h.cue == Cue.SHIFT)) h.targetWheelDeg else null
        left = if (guided && s.phase == Phase.PARKING && h != null && (h.cue == Cue.MOVE || (h.cue == Cue.FIND_SPOT && h.box != null))) h.remaining else null
    }
}

/** What to do now, in words: the same on the full screen and in the small window that stays up in reverse. */
@Composable
fun NowCard(s: UiState, e: Engine, hint: Hint?) {
    when {
        s.doorOpen -> Instruction("안전", Texts.DOOR_OPEN, "", Palette.Slow)
        s.missing != null -> Instruction("신호", "${s.missing}를 아직 읽지 못했습니다", "", Palette.Watch)
        e.tooFast -> Instruction("속도", Texts.TOO_FAST, "", Palette.Watch)
        s.phase == Phase.PARKING && s.assist == Assist.SOLO ->
            Instruction("혼자 하기", "안내 없이 넣어 보세요", "", Palette.Accent)
        s.phase == Phase.PARKING && hint != null && s.assist == Assist.PATH ->
            Instruction("길만 보기", "초록 띠를 따라가세요", "", Palette.Go)
        s.phase == Phase.PARKING && hint != null -> Instruction(
            stepName(hint),
            Texts.headline(hint),
            Texts.figure(hint),
            if (hint.cue == Cue.STOP) Palette.Slow else Palette.Go,
            if (hint.cue == Cue.SHIFT) Texts.CHECK_FIRST else Texts.steerNote(hint),
        )
        s.phase == Phase.PARKING -> Instruction("준비", "차를 멈추면 안내가 시작됩니다", "", Palette.Accent)
        s.phase == Phase.RECORDING -> Instruction("기록 중", Texts.stats(DriveStats.of(e.trail)), "", Palette.Slow)
        s.phase == Phase.READY -> Instruction("준비", "칸에서 나갔다가 다시 주차하세요", "", Palette.Accent)
        s.phase == Phase.LEAVING -> Instruction("출발", "칸 밖에서 멈추면 안내가 시작됩니다", "", Palette.Accent)
        s.level == Level.OPEN -> Instruction(s.chosen?.name ?: "", Texts.placeCar(s.openSpace), "", Palette.Accent)
        else -> Instruction("첫 주차", "평소대로 주차하는 모습을 한 번 기록합니다", "", Palette.Accent)
    }
}

/**
 * The wheel turning live, and in its hub whatever is still left to do: turns of the wheel first,
 * then metres to go. [wheel] is its size in dp.
 */
@Composable
fun WheelGauge(e: Engine, read: Readout, wheel: Int, beat: Float) {
    val scale = wheel / 320f
    val target = read.steerTarget
    val left = read.left
    Box(Modifier.size(wheel.dp).glass(wheel / 2), contentAlignment = Alignment.Center) {
        SteeringDial(e.wheelDeg, target, Modifier.fillMaxSize(), beat)
        val more = if (target != null) target - e.wheelDeg else 0.0
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (abs(more) > TURN_MORE) {
                Text(Texts.turns(more), color = Palette.Go, fontSize = (29 * scale).sp, fontWeight = FontWeight.SemiBold)
                Text("${Texts.side(more)}으로", color = Palette.Dim, fontSize = (16 * scale).sp)
            } else if (left != null) {
                Text(Texts.metres(left), color = Palette.Text, fontSize = (35 * scale).sp, fontWeight = FontWeight.Light)
                Text(Texts.LEFT_TO_GO, color = Palette.Dim, fontSize = (16 * scale).sp)
            } else {
                Text("${e.wheelDeg.roundToInt()}°", color = Palette.Text, fontSize = (36 * scale).sp, fontWeight = FontWeight.Light)
            }
        }
    }
}

/** Width in dp of the strip down the left edge that the driver's cards float over. */
private const val COLUMN = 500

/**
 * The moving picture fills the whole screen, edge to edge, with the car in the middle of the part
 * nothing covers. Everything the driver reads or presses floats over the left side, the side they
 * sit on: what to do now at the top, and below it one instrument that says it all at a glance: the
 * gear, the steering wheel, and in its hub how far there is still to turn or to go.
 */
@Composable
fun DriveScreen(s: UiState, core: AppCore) {
    val e = core.engine
    val read = Readout(s, e)
    val hint = read.hint
    val showGhost = s.phase == Phase.PARKING || s.phase == Phase.LEAVING || s.phase == Phase.READY

    // A shorter screen gets a smaller wheel, so the buttons under it are never pushed off the bottom.
    val height = LocalStageHeight.current
    val wheel = if (height >= 900) 320 else if (height >= 760) 260 else 200
    val beat = ((core.clock % TURN_BEAT) / TURN_BEAT).toFloat()

    Box(Modifier.fillMaxSize()) {
        OrbitScene(Modifier.fillMaxSize()) { tilt, spin ->
            SceneData(
                spec = s.spec,
                pose = e.pose,
                wheelDeg = e.wheelDeg,
                lot = e.lot,
                trail = e.trail,
                ghost = if (showGhost) e.baseline ?: e.history.firstOrNull()?.samples ?: emptyList() else emptyList(),
                path = if (s.assist == Assist.SOLO) emptyList() else hint?.path ?: emptyList(),
                box = if (s.assist == Assist.SOLO) null else hint?.box,
                hit = e.hit,
                tilt = tilt,
                spin = spin,
                frame = s.frame,
                sensors = s.phase == Phase.PARKING || s.phase == Phase.LEAVING,
                leftInset = COLUMN.toFloat(),
                follow = true,
                limit = e.limit,
            )
        }

        // While the car moves the picture steps back: the mirrors are where to look, not the screen.
        // The instruction, the wheel and the voice carry on.
        val rolling = abs(e.speed) > 0.25 && (s.phase == Phase.PARKING || s.phase == Phase.LEAVING)
        if (s.eyesOut && rolling) {
            Box(Modifier.fillMaxSize().background(Palette.Bg.copy(alpha = 0.62f)).padding(start = COLUMN.dp), contentAlignment = Alignment.Center) {
                Text(Texts.LOOK_OUT, color = Palette.Text, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Column(Modifier.align(Alignment.TopStart).fillMaxHeight().width(COLUMN.dp).padding(22.dp)) {
            // Back, how fast, and the nearest virtual obstacle, like the read-out of a parking sensor.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuietButton("‹", { core.back() })
                Box(Modifier.glass(40).padding(horizontal = 22.dp, vertical = 14.dp)) {
                    Text("${(abs(e.speed) * 3.6).roundToInt()} km/h", color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                }
                val gap = e.clearance
                if (gap < 1.5 && (s.phase == Phase.PARKING || s.phase == Phase.LEAVING)) {
                    val tone = if (gap < 0.35) Palette.Slow else if (gap < 0.7) Palette.Watch else Palette.Go
                    Box(Modifier.glass(40).padding(horizontal = 22.dp, vertical = 14.dp)) {
                        Text(Texts.centimetres(gap), color = tone, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            // What to do now.
            Column(Modifier.fillMaxWidth().glass(36).padding(horizontal = 28.dp, vertical = 22.dp)) {
                NowCard(s, e, hint)
                // Waiting in the space: where the real cones go, if there are any, and what the dashed line is.
                val place = e.lot
                val cones = if (s.cones && place != null && s.phase == Phase.READY) {
                    // The first cone as the parked car sees it: so far ahead of (or behind) the rear axle, so far to the side.
                    val first = place.conePlaces().first()
                    val spot = place.target.relative(Pose(first.x, first.y, 0.0))
                    val front = spot.x >= 0
                    val beyond = if (front) spot.x - (s.spec.centerAhead + s.spec.length / 2) else -spot.x - (s.spec.length / 2 - s.spec.centerAhead)
                    Texts.coneHint(abs(spot.y) - s.spec.width / 2, beyond, front, s.openSpace, place.config.type == SlotType.PARALLEL)
                } else {
                    null
                }
                val line = cones ?: s.notice ?: s.problem ?: if (e.limit != null && s.phase == Phase.READY) Texts.LIMIT else null
                if (line != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(line, color = Palette.Accent, fontSize = 21.sp, lineHeight = 29.sp)
                }
            }
            Spacer(Modifier.weight(1f))

            // The instrument: the gear on top, the wheel turning live, and in its hub whatever is
            // still left to do: turns of the wheel first, then metres to go.
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                GearStrip(s.gear, if (s.assist == Assist.SOLO) null else read.wanted)
                Spacer(Modifier.height(10.dp))
                WheelGauge(e, read, wheel, beat)
            }
            Spacer(Modifier.height(14.dp))

            // What can be pressed, by stage.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val parked = s.gear == Gear.PARK
                // Without the steering angle and the speed nothing can be tracked, so nothing starts.
                val reading = s.missing == null
                when (s.phase) {
                    Phase.IDLE -> if (s.level == Level.OPEN) {
                        BigButton("여기를 주차 칸으로", { core.setTargetHere() }, Modifier.fillMaxWidth(), enabled = parked && reading && s.chosen != null)
                    } else {
                        BigButton("첫 주차 기록 시작", { core.startRecording() }, Modifier.fillMaxWidth(), enabled = reading)
                        QuietButton("이미 주차했어요", { core.describeHere() }, Modifier.fillMaxWidth(), enabled = parked && reading)
                    }
                    Phase.READY, Phase.LEAVING -> {
                        if (s.phase == Phase.READY) {
                            QuietButton(if (s.cones) "콘 치우기" else "콘 두 개 세우기", { core.setCones(!s.cones) }, Modifier.fillMaxWidth())
                        }
                        if (s.demo && s.phase == Phase.READY) {
                            BigButton(if (s.autopilot) "시범 멈춤" else "시범 보기", { core.toggleAutopilot() }, Modifier.fillMaxWidth())
                        }
                    }
                    else -> Unit
                }
                if (e.lot != null || s.phase == Phase.PARKING) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (e.lot != null) QuietButton(Texts.assistName(s.assist), { core.cycleAssist() }, Modifier.weight(1f))
                        if (s.phase == Phase.PARKING) QuietButton("처음부터 다시", { core.again() }, Modifier.weight(1f))
                    }
                }
            }
        }

        if (!s.checked) SafetyCheck { core.confirmSafety() }

        // The standing reminder, small at the foot of the picture.
        Text(
            "화면을 끌면 입체로, 두 번 누르면 위에서 봅니다\n${Texts.SAFETY}",
            color = Palette.Dim.copy(alpha = 0.8f),
            fontSize = 15.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.align(Alignment.BottomEnd).padding(22.dp).widthIn(max = 430.dp),
        )
    }
}
