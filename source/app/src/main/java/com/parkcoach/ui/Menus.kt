package com.parkcoach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parkcoach.BuildConfig
import com.parkcoach.core.CarSize
import com.parkcoach.vehicle.SteeringUnit
import com.parkcoach.core.Course
import com.parkcoach.core.DriveStats
import com.parkcoach.core.Gear
import com.parkcoach.core.LotBuilder
import com.parkcoach.core.LotConfig
import com.parkcoach.core.Opposite
import com.parkcoach.core.Pose
import com.parkcoach.core.Sample
import com.parkcoach.core.Scenarios
import com.parkcoach.core.Side
import com.parkcoach.core.SlotType
import com.parkcoach.core.Spot
import com.parkcoach.core.Stance
import com.parkcoach.core.VehicleSpec
import com.parkcoach.core.Vehicles
import com.parkcoach.core.wrap
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt

private fun stars(n: Int): String = "★".repeat(n) + "☆".repeat(3 - n)

private fun one(v: Double): String = String.format(Locale.US, "%.1f", v)

/** A still picture of a situation with the car parked in it. */
@Composable
private fun LotPreview(config: LotConfig, spec: VehicleSpec, modifier: Modifier = Modifier) {
    val lot = remember(config, spec) { LotBuilder.build(config, spec, Pose(0.0, 0.0, PI / 2)) }
    SceneView(SceneData(spec, lot.target, 0.0, lot, tilt = 0.7f, spin = 0.35f), modifier.clip(RoundedCornerShape(22.dp)))
}

// ---------------------------------------------------------------------- Home

/** A place as the picture at the top of a card: no car parks are alike, so the two cards show two. */
private val VIRTUAL_PICTURE = LotConfig("picture_virtual", "", opposite = Opposite.CARS, aisle = 6.0)
private val OWN_PICTURE = LotConfig("picture_own", "", left = Side.PILLAR, right = Side.CAR, opposite = Opposite.WALL, aisle = 6.0, rightSize = CarSize.LARGE)

/**
 * The first screen. Someone who has never seen the app has two things to choose between and nothing
 * else to read first: practise in a car park that is only on the screen, or teach the app the place
 * they park in every day. Each is a picture, one line of what it is, and one button. Everything
 * that is set once and left alone (voice, colours, the car) is a step away, not in the way.
 */
@Composable
fun HomeScreen(s: UiState, core: AppCore) {
    Column(Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 30.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Texts.APP, color = Palette.Text, fontSize = 52.sp, fontWeight = FontWeight.SemiBold)
                Text(Texts.TAGLINE, color = Palette.Dim, fontSize = 23.sp)
            }
            // The car the app is working with, and the way to change it.
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Palette.Panel)
                    .border(1.dp, Hairline, RoundedCornerShape(50))
                    .clickable { core.open(Screen.GARAGE) }
                    .padding(horizontal = 26.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("내 차", color = Palette.Dim, fontSize = 20.sp)
                Spacer(Modifier.width(12.dp))
                Text(s.spec.name, color = Palette.Text, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.width(12.dp))
                Text(if (s.specFromCar) "차에서 읽음" else if (s.demo) "데모 차량" else "직접 고름", color = Palette.Dim, fontSize = 20.sp, maxLines = 1)
                Spacer(Modifier.width(14.dp))
                Text("›", color = Palette.Dim, fontSize = 26.sp)
            }
            Spacer(Modifier.width(14.dp))
            QuietButton("설정", { core.open(Screen.SETTINGS) })
        }
        Spacer(Modifier.height(26.dp))

        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            // 1. A car park that is only on the screen.
            val passed = Course.order.count { it in s.passed }
            val next = Course.order.firstOrNull { it !in s.passed }
            val begun = passed > 0 || s.best.values.any { it > 0 }
            ModeCard(
                tag = "가상 주차장",
                tint = Palette.Go,
                title = "가상 주차장에서 연습",
                body = "옆 차와 벽은 화면에만 있어요. 닿아도 긁히지 않으니 마음 놓고 여러 번 해 보세요.",
                picture = Scenarios.byId(next)?.takeIf { begun } ?: VIRTUAL_PICTURE,
                spec = s.spec,
                action = if (begun) "이어서 연습" else "연습 시작",
                onAction = { core.pickLevel(Level.OPEN) },
            ) {
                Label("어디에서 하나요?")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlaceTile("빈 주차 칸", "실제 주차선 위에서", !s.openSpace, Palette.Go) { core.setOpenSpace(false) }
                    PlaceTile("넓은 공터", "선도 화면에만", s.openSpace, Palette.Go) { core.setOpenSpace(true) }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${Course.order.size}단계 중 ${passed}단계 통과", color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (next == null) "모든 단계를 통과했어요" else "다음  ${Course.label(next)} ${Scenarios.byId(next)?.name ?: ""}",
                        color = Palette.Dim,
                        fontSize = 22.sp,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(Palette.Soft)) {
                    Box(Modifier.fillMaxWidth(passed.toFloat() / Course.order.size).fillMaxHeight().background(Palette.Go))
                }
            }
            // 2. The place the driver parks in every day.
            ModeCard(
                tag = "내 주차 자리",
                tint = Palette.Accent,
                title = "내 자리에서 연습",
                body = "늘 대는 그 자리를 한 번 기록해 두면, 그 자리에 맞는 길을 알려 줍니다.",
                picture = OWN_PICTURE,
                spec = s.spec,
                action = "내 자리 기록하기",
                onAction = { core.pickLevel(Level.REAL) },
            ) {
                Step(1, "평소대로 한 번 주차하면 그 길을 기록해요", Palette.Accent)
                Spacer(Modifier.height(10.dp))
                Step(2, "옆 차와 벽이 어디 있는지 알려 주세요", Palette.Accent)
                Spacer(Modifier.height(10.dp))
                Step(3, "다음부터는 한 번에 넣는 길을 안내해요", Palette.Accent)
            }
        }
        Spacer(Modifier.height(20.dp))

        // What happens, in the three things the driver does, for someone opening the app for the first time.
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Palette.Soft).padding(horizontal = 28.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill("처음이라면", Palette.Text)
            Spacer(Modifier.width(20.dp))
            Text("빈 칸에 차를 세우고", color = Palette.Text, fontSize = 23.sp, maxLines = 1)
            Text("   →   ", color = Palette.Dim, fontSize = 23.sp)
            Text("한 번 나갔다가", color = Palette.Text, fontSize = 23.sp, maxLines = 1)
            Text("   →   ", color = Palette.Dim, fontSize = 23.sp)
            Text("화면과 음성을 따라 다시 주차", color = Palette.Text, fontSize = 23.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text("주변은 항상 직접 확인하세요", color = Palette.Dim, fontSize = 20.sp, maxLines = 1)
        }
    }
}

/**
 * One of the two things to do from the first screen: a picture, what it is, and its one button.
 * [details] is the little that is particular to it, between the words and the button.
 */
@Composable
private fun RowScope.ModeCard(
    tag: String,
    tint: Color,
    title: String,
    body: String,
    picture: LotConfig,
    spec: VehicleSpec,
    action: String,
    onAction: () -> Unit,
    details: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(40.dp))
            .background(Palette.Panel)
            .border(1.dp, Hairline, RoundedCornerShape(40.dp)),
    ) {
        // The picture is drawn to the edge of what it shows, so it is cut off at the edge of its own box.
        Box(Modifier.weight(1f).fillMaxWidth().clip(RectangleShape).clickable(onClick = onAction)) {
            val lot = remember(picture, spec) { LotBuilder.build(picture, spec, Pose(0.0, 0.0, PI / 2)) }
            SceneView(SceneData(spec, lot.target, 0.0, lot, tilt = 0.7f, spin = 0.35f), Modifier.fillMaxSize())
            Pill(tag, tint, Modifier.align(Alignment.TopStart).padding(24.dp))
        }
        Column(Modifier.fillMaxWidth().padding(start = 34.dp, end = 34.dp, top = 24.dp, bottom = 30.dp)) {
            Text(title, color = Palette.Text, fontSize = 44.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Text(body, color = Palette.Dim, fontSize = 25.sp, lineHeight = 36.sp)
            Spacer(Modifier.height(18.dp))
            details()
            Spacer(Modifier.height(22.dp))
            BigButton(action, onAction, Modifier.fillMaxWidth(), color = tint)
        }
    }
}

/** One of two places to practise in: the one chosen is outlined in the card's colour. */
@Composable
private fun RowScope.PlaceTile(title: String, note: String, chosen: Boolean, tint: Color, onPick: () -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(24.dp))
            .background(if (chosen) tint.copy(alpha = 0.12f) else Palette.Soft)
            .border(2.dp, if (chosen) tint else Color.Transparent, RoundedCornerShape(24.dp))
            .clickable(onClick = onPick)
            .padding(horizontal = 22.dp, vertical = 16.dp),
    ) {
        Text(title, color = Palette.Text, fontSize = 27.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(note, color = Palette.Dim, fontSize = 21.sp, maxLines = 1)
    }
}

/** One numbered thing that will happen. */
@Composable
private fun Step(number: Int, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Text("$number", color = tint, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Text(text, color = Palette.Text, fontSize = 25.sp, maxLines = 1)
    }
}

// ---------------------------------------------------------------------- Settings

/** Everything that is set once and then left alone, kept off the first screen. */
@Composable
fun SettingsScreen(s: UiState, core: AppCore) {
    ScreenFrame("설정", { core.back() }) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Panel(Modifier.weight(1.2f)) {
                Setting("음성 안내", "할 일을 말로도 알려 줍니다", s.voice) { core.setVoice(it) }
                Setting("밝은 화면", "끄면 어두운 화면으로 바뀝니다", Palette.light) { core.setLight(it) }
                Setting("움직일 때 화면 흐리게", "차가 움직이는 동안은 그림을 가리고 \"거울과 주변을 보세요\"만 남깁니다", s.eyesOut) { core.setEyesOut(it) }
                Setting(Texts.FLOATING, "후진하는 동안 후방 카메라 위 구석에 안내를 띄웁니다", s.floating && s.floatAllowed) { core.setFloating(it) }
                if (s.floating && !s.floatAllowed) Label(Texts.FLOATING_NEEDS)
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Panel(Modifier.fillMaxWidth()) {
                    Text("연습 장소", color = Palette.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    Label("연습하기 좋았던 곳을 저장해 두고 다시 찾아갑니다")
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        QuietButton("연습 장소 보기", { core.openPlaces() })
                        // For whoever is building or testing the app: every signal as the car reports it.
                        if (BuildConfig.DEBUG) QuietButton("신호 확인", { core.open(Screen.SIGNALS) })
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(Texts.SAFETY, color = Palette.Dim, fontSize = 20.sp, lineHeight = 29.sp)
                Spacer(Modifier.height(10.dp))
                Text(Texts.CREDIT, color = Palette.Dim.copy(alpha = 0.8f), fontSize = 16.sp, lineHeight = 23.sp)
            }
        }
    }
}

/** One switch with what it does said underneath. */
@Composable
private fun Setting(title: String, note: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.Text, fontSize = 27.sp, fontWeight = FontWeight.Medium)
            Text(note, color = Palette.Dim, fontSize = 20.sp, lineHeight = 28.sp)
        }
        Spacer(Modifier.width(20.dp))
        Switch(checked = on, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------------- Level 1: choose a situation

/**
 * The course: every lesson in order, easiest first. A lesson shows how far it has been taken
 * (guided, path only, test) and its best stars. One whose turn has not come is covered over until
 * the test before it is passed, unless the driver asks for all of them to be open.
 */
@Composable
fun ScenariosScreen(s: UiState, core: AppCore) {
    ScreenFrame("주차 코스", { core.back() }) {
        val all = Course.order.mapNotNull { Scenarios.byId(it) }
        for (row in all.chunked(5)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (config in row) {
                    val open = core.unlocked(config.id)
                    val done = config.id in s.passed
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .glass(30)
                            .clickable(enabled = open) { core.pickScenario(config) }
                            .padding(12.dp),
                    ) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            LotPreview(config, s.spec, Modifier.fillMaxSize())
                            if (!open) {
                                Box(
                                    Modifier.fillMaxSize().background(Palette.Bg.copy(alpha = 0.80f), RoundedCornerShape(22.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("앞 단계 시험을 통과하면 열려요", color = Palette.Dim, fontSize = 18.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${Course.label(config.id)}  ${config.name}",
                            color = if (open) Palette.Text else Palette.Dim,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stars(s.best[config.id] ?: 0), color = Palette.Watch, fontSize = 22.sp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (done) "시험 통과" else if (open) Texts.round(s.stages[config.id] ?: Assist.FULL) else "",
                                color = if (done) Palette.Go else Palette.Dim,
                                fontSize = 17.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
                for (blank in row.size until 5) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(s.custom.name, color = Palette.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Label(Texts.describe(s.custom) + "  " + stars(s.best[s.custom.id] ?: 0))
            }
            QuietButton(if (s.openAll) "순서대로 하기" else "모두 열기", { core.toggleOpenAll() })
            QuietButton("고치기", { core.editCustom() })
            BigButton("내 주차장으로 연습", { core.pickScenario(s.custom) })
        }
    }
}

// ---------------------------------------------------------------------- Signals (for building and testing)

/**
 * Every signal and permission as it stands this instant. It is how a silent failure shows: a value
 * that never arrives says so here instead of quietly reading as zero.
 */
@Composable
fun SignalsScreen(s: UiState, core: AppCore) {
    ScreenFrame("신호 확인", { core.back() }) {
        // Reading the frame count keeps the page live: it is redrawn as often as the car is read.
        val rows = remember(s.frame) { core.signals() }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1.4f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                for ((name, value) in rows) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(name, color = Palette.Dim, fontSize = 21.sp, modifier = Modifier.weight(1f))
                        Text(value, color = if (value.contains("못함") || value == "없음") Palette.Slow else Palette.Text, fontSize = 21.sp, modifier = Modifier.weight(1.2f))
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Label("핸들을 오른쪽으로 돌렸는데 화면의 핸들이 왼쪽으로 돌면 아래 스위치를 바꾸세요. 차마다 부호가 다릅니다.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("오른쪽 회전이 음수", color = Palette.Text, fontSize = 24.sp)
                    Spacer(Modifier.width(14.dp))
                    Switch(checked = s.rightNegative, onCheckedChange = { core.setRightNegative(it) })
                }
                Label("핸들을 끝까지 돌려도 화면의 핸들이 조금만 돌면 '앞바퀴 각도'를, 너무 많이 돌면 '핸들 각도'를 고르세요.")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip("자동", s.steeringUnit == SteeringUnit.AUTO) { core.setSteeringUnit(SteeringUnit.AUTO) }
                    Chip("앞바퀴 각도", s.steeringUnit == SteeringUnit.FRONT_WHEELS) { core.setSteeringUnit(SteeringUnit.FRONT_WHEELS) }
                    Chip("핸들 각도", s.steeringUnit == SteeringUnit.STEERING_WHEEL) { core.setSteeringUnit(SteeringUnit.STEERING_WHEEL) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("데모 차량으로 실행", color = Palette.Text, fontSize = 24.sp)
                    Spacer(Modifier.width(14.dp))
                    Switch(checked = s.demo, onCheckedChange = { if (it) core.setForceDemo(true) else core.retryCar() })
                }
                QuietButton("차량 다시 연결", { core.retryCar() })
                QuietButton("작은 안내 창 띄워 보기", { core.tryFloating() })
                Spacer(Modifier.weight(1f))
                Label("신호가 '아직 읽지 못함'이면 연습 화면은 안내를 멈추고 어느 신호인지 알려 줍니다.")
            }
        }
    }
}

// ---------------------------------------------------------------------- Places to practise at

/** Places the driver saved, each one tap from the car's navigation. */
@Composable
fun PlacesScreen(s: UiState, core: AppCore) {
    ScreenFrame("연습 장소", { core.back() }) {
        Body("연습하기 좋았던 곳에 차를 세운 채 저장해 두면, 다음에는 내비게이션으로 바로 찾아갑니다. 연습해도 되는 곳만 저장하세요.", color = Palette.Dim)
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (s.places.isEmpty()) Label("아직 저장한 곳이 없어요.")
            for (place in s.places) {
                Row(
                    Modifier.fillMaxWidth().glass(26).padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(place.name, color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                        Label(String.format(java.util.Locale.US, "%.5f, %.5f", place.lat, place.lng))
                    }
                    QuietButton("지우기", { core.removePlace(place) })
                    BigButton("길 안내", { core.navigateTo(place) })
                }
            }
        }
        val line = s.notice
        if (line != null) Label(line)
        Spacer(Modifier.height(12.dp))
        BigButton("지금 있는 곳 저장", { core.savePlace() }, Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------------- Custom lot, and questions about a real one

@Composable
private fun <T> Choice(title: String, options: List<Pair<T, String>>, selected: T?, strong: Boolean = false, onPick: (T) -> Unit) {
    // The one question everything else hangs on is asked in a voice to match.
    if (strong) Text(title, color = Palette.Text, fontSize = 27.sp, fontWeight = FontWeight.SemiBold) else Label(title)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((value, name) in options) Chip(name, value == selected) { onPick(value) }
    }
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun Dial(title: String, value: Double, from: Double, to: Double, onChange: (Double) -> Unit) {
    Label("$title  ${one(value)} m")
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange((it * 10).roundToInt() / 10.0) },
        valueRange = from.toFloat()..to.toFloat(),
    )
}

/** What may stand beside the space for this way of parking. Slanted bays and kerbsides only take cars. */
private fun sidesFor(type: SlotType): List<Pair<Side, String>> =
    if (type == SlotType.PARALLEL || type == SlotType.ANGLED) {
        listOf(Side.CAR to "차")
    } else {
        listOf(Side.CAR to "차", Side.WALL to "벽", Side.PARTITION to "칸막이", Side.PILLAR to "기둥")
    }

private val acrossChoices = listOf(Opposite.CARS to "주차된 차", Opposite.WALL to "벽")

private fun taken(c: LotConfig, spot: Spot): Boolean = when (spot) {
    Spot.LEFT -> c.left != Side.EMPTY
    Spot.RIGHT -> c.right != Side.EMPTY
    Spot.OPPOSITE -> c.opposite != Opposite.OPEN
}

private fun cleared(c: LotConfig, spot: Spot): LotConfig = when (spot) {
    Spot.LEFT -> c.copy(left = Side.EMPTY)
    Spot.RIGHT -> c.copy(right = Side.EMPTY)
    Spot.OPPOSITE -> c.copy(opposite = Opposite.OPEN)
}

/** Something newly put down is an ordinary car parked straight until it is told otherwise. */
private fun placed(c: LotConfig, spot: Spot, side: Side): LotConfig = if (spot == Spot.LEFT) {
    c.copy(left = side, leftSize = CarSize.NORMAL, leftStance = Stance.STRAIGHT)
} else {
    c.copy(right = side, rightSize = CarSize.NORMAL, rightStance = Stance.STRAIGHT)
}

private fun sized(c: LotConfig, spot: Spot, size: CarSize): LotConfig = if (spot == Spot.LEFT) c.copy(leftSize = size) else c.copy(rightSize = size)

private fun stood(c: LotConfig, spot: Spot, stance: Stance): LotConfig = if (spot == Spot.LEFT) c.copy(leftStance = stance) else c.copy(rightStance = stance)

/** Changing the way of parking keeps what still fits and clears what does not. */
private fun retyped(c: LotConfig, type: SlotType): LotConfig {
    val allowed = sidesFor(type).map { it.first }
    fun kept(side: Side) = if (side == Side.EMPTY || side in allowed) side else Side.EMPTY
    val parallel = type == SlotType.PARALLEL
    return c.copy(type = type, room = if (parallel) 2.8 else 2.5, aisle = if (parallel) 5.0 else 6.0, left = kept(c.left), right = kept(c.right))
}

/**
 * The sizes offered when a lot is built by hand. Bays are the three widths Korean car parks have
 * been marked out to: 2.3 m before 2019, 2.5 m since, 2.6 m for the wide kind.
 */
private object Sizes {
    val BAY = listOf(2.3 to "2.3 m · 옛 주차장", 2.5 to "2.5 m · 일반형", 2.6 to "2.6 m · 확장형")
    val AISLE = listOf(5.0 to "좁음 5 m", 6.0 to "보통 6 m", 7.0 to "넓음 7 m")
    val ROAD = listOf(4.0 to "좁음 4 m", 5.0 to "보통 5 m", 6.0 to "넓음 6 m")
    val GAP = listOf(2.3 to "빠듯 2.3 m", 2.8 to "보통 2.8 m", 3.4 to "넉넉 3.4 m")
}

/**
 * Building a lot, or describing a real one. The way of parking and the widths are set on the left;
 * everything that stands around the space is put there by touching the picture: a plus adds, a
 * cross takes away.
 */
@Composable
fun EditorScreen(s: UiState, core: AppCore) {
    val c = s.editing ?: return
    val real = s.level == Level.REAL
    val parallel = c.type == SlotType.PARALLEL
    // A real place cannot be drawn until it is known how the car went in: that is what puts the
    // lane in front of the car or behind it.
    val known = !real || s.entryKnown
    val layer = remember { EditLayer() }
    var open by remember { mutableStateOf<Spot?>(null) }
    layer.open = open
    ScreenFrame(if (real) "주변이 어떻게 생겼나요?" else "내 주차장 만들기", { core.back() }) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Choice(
                    if (real) "차를 어떻게 넣었나요?" else "주차 방식",
                    if (real) {
                        listOf(
                            SlotType.PERPENDICULAR to "후진으로", SlotType.NOSE_IN to "앞으로",
                            SlotType.ANGLED to "사선 칸에", SlotType.PARALLEL to "길가에 나란히",
                        )
                    } else {
                        listOf(
                            SlotType.PERPENDICULAR to "후진", SlotType.NOSE_IN to "전면",
                            SlotType.ANGLED to "사선", SlotType.PARALLEL to "평행",
                        )
                    },
                    if (known) c.type else null,
                    strong = real,
                ) {
                    open = null
                    // Which way round a kerbside or slanted space lies is kept only where the recorded drive says so.
                    val read = core.entry?.takeIf { r -> r.type == it }
                    core.updateEditing(retyped(c, it).copy(mirror = read?.mirror ?: false, angle = read?.angle ?: c.angle))
                    core.entryAnswered()
                }
                if (real && s.entryRead) Label("처음 주차한 길을 보고 골랐어요. 다르면 바꿔 주세요.", color = Palette.Accent)
                if (!known) Label("먼저 골라 주세요. 통로가 차 앞에 있는지 뒤에 있는지가 여기서 정해집니다.", color = Palette.Accent)
                if (real && (s.entryRead || !known)) Spacer(Modifier.height(14.dp))
                if (c.type == SlotType.ANGLED) Choice("기울기", listOf(45.0 to "45°", 60.0 to "60°"), c.angle) { core.updateEditing(c.copy(angle = it)) }
                // Which way round the place lies, for the two kinds of space that can lie either way.
                if (known && c.type == SlotType.ANGLED) {
                    Choice("들어갈 때 어느 쪽으로 꺾나요?", listOf(false to "왼쪽으로", true to "오른쪽으로"), c.mirror) { core.updateEditing(c.copy(mirror = it)) }
                }
                if (known && parallel) {
                    Choice("차로는 차의 어느 쪽인가요?", listOf(false to "왼쪽", true to "오른쪽"), c.mirror) { core.updateEditing(c.copy(mirror = it)) }
                }
                // Nobody knows their car park in metres, so the sizes are the few that car parks
                // are actually built to. A real lot's lane is not asked at all: the first drive shows it.
                if (!real) {
                    Choice(if (parallel) "도로 폭" else "통로 폭", if (parallel) Sizes.ROAD else Sizes.AISLE, c.aisle) { core.updateEditing(c.copy(aisle = it)) }
                }
                if (parallel) {
                    Choice("앞뒤 여유", Sizes.GAP, c.room) { core.updateEditing(c.copy(room = it)) }
                } else {
                    Choice("칸 폭", Sizes.BAY, c.room) { core.updateEditing(c.copy(room = it)) }
                }
                Spacer(Modifier.height(10.dp))
                Label("그림의 + 를 누르면 차, 벽, 기둥을 놓을 수 있어요. × 를 누르면 치웁니다.")
                Label("옆 차를 누르면 크기와 세워 둔 모양을 바꿀 수 있어요.")
                if (real) Label("통로 폭은 묻지 않습니다. 처음 주차할 때 차가 지나간 범위 안에서만 안내합니다.")
            }
            Column(Modifier.weight(1.1f).fillMaxHeight()) {
                // A real place is drawn round the car where it stands, with the first parking recorded
                // there on top: the driver sees their own drive come in along the lane.
                val drive = if (real) core.engine.baseline ?: emptyList() else emptyList()
                val lot = remember(c, s.spec, real, drive) {
                    if (real) core.engine.preview(c) else LotBuilder.build(c, s.spec, Pose(0.0, 0.0, PI / 2))
                }
                // That drive really happened, so it went through no wall. If it does here, the place is described wrongly.
                val wrong = remember(c, s.spec, real, drive, known) { real && known && core.engine.misfit(c) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    SceneView(
                        SceneData(s.spec, lot.target, 0.0, lot, trail = if (known) drive else emptyList(), tilt = 0.7f, spin = 0.35f, edit = if (known) layer else null),
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(22.dp))
                            .pointerInput(c, known) {
                                detectTapGestures(onTap = { at ->
                                    if (!known) return@detectTapGestures
                                    val spot = layer.at(at)
                                    val sides = sidesFor(c.type)
                                    when {
                                        // Not on a mark: on a parked car it opens that car's choices.
                                        spot == null -> open = layer.car(at)
                                        // A cross: take away what stands there.
                                        taken(c, spot) -> {
                                            open = null
                                            core.updateEditing(cleared(c, spot))
                                        }
                                        // Only a car can go there: put it down and show what kind.
                                        spot != Spot.OPPOSITE && sides.size == 1 -> {
                                            open = spot
                                            core.updateEditing(placed(c, spot, sides.first().first))
                                        }
                                        else -> open = spot
                                    }
                                })
                            },
                    )
                    if (!known) {
                        Box(
                            Modifier.fillMaxSize().background(Palette.Bg.copy(alpha = 0.86f), RoundedCornerShape(22.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("차를 어떻게 넣었는지 먼저 골라 주세요", color = Palette.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (wrong) {
                    Spacer(Modifier.height(10.dp))
                    Label("처음 주차한 길이 벽을 지나갑니다. 차를 넣은 방향이나 벽을 놓은 자리를 다시 봐 주세요.", color = Palette.Slow)
                }
                Spacer(Modifier.height(12.dp))
                // What to put on the place that was touched. The row keeps its height so nothing jumps.
                Row(
                    Modifier.fillMaxWidth().height(58.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val at = open
                    if (at == null) {
                        Label("+ 를 눌러 놓을 자리를 고르세요")
                    } else if (at != Spot.OPPOSITE && c.side(at) == Side.CAR) {
                        // A car is standing there: how big it is, and how it was left.
                        for (size in CarSize.values()) Chip(Texts.sizeName(size), c.size(at) == size) { core.updateEditing(sized(c, at, size)) }
                        Spacer(Modifier.width(8.dp))
                        for (stance in Stance.values()) Chip(Texts.stanceName(stance, parallel), c.stance(at) == stance) { core.updateEditing(stood(c, at, stance)) }
                    } else {
                        Label("${Texts.spot(at, parallel)}에")
                        if (at == Spot.OPPOSITE) {
                            for ((value, name) in acrossChoices) Chip(name, false) {
                                open = null
                                core.updateEditing(c.copy(opposite = value))
                            }
                        } else {
                            for ((value, name) in sidesFor(c.type)) Chip(name, false) {
                                // A car stays chosen, so its size and the way it stands can be set next.
                                open = if (value == Side.CAR) at else null
                                core.updateEditing(placed(c, at, value))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                BigButton(if (real) "이 자리 기억하기" else "이대로 연습", { core.saveEditing() }, Modifier.fillMaxWidth(), enabled = known)
            }
        }
    }
}

// ---------------------------------------------------------------------- Result and replay

@Composable
private fun Fact(value: String, caption: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, color = Palette.Text, fontSize = 38.sp, fontWeight = FontWeight.Light, maxLines = 1)
        Text(caption, color = Palette.Dim, fontSize = 19.sp, maxLines = 1)
    }
}

@Composable
fun ResultScreen(s: UiState, core: AppCore) {
    val e = core.engine
    val a = e.last
    if (a == null) {
        ScreenFrame("결과", { core.back() }) { Body("아직 기록이 없어요.") }
        return
    }
    // The very first attempt, recorded or unguided, is what every later one is measured against.
    val first = e.baseline?.let { DriveStats.of(it) } ?: e.history.firstOrNull()?.takeIf { e.history.size > 1 }?.stats
    Row(
        Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text(stars(a.stars), color = Palette.Watch, fontSize = 60.sp)
            Text(
                Texts.verdict(a),
                color = if (a.success) Palette.Go else Palette.Slow,
                fontSize = 44.sp,
                fontWeight = FontWeight.SemiBold,
            )
            val line = s.notice
            if (line != null) Text(line, color = Palette.Accent, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            val struck = e.hit
            if (struck != null) Label("닿은 곳: ${Texts.hitName(struck.kind)}")
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth()) {
                Fact("${a.stats.moves}번", "전후진", Modifier.weight(1f))
                Fact(Texts.centimetres(a.sideways), "좌우로 벗어남", Modifier.weight(1f))
                Fact("${one(a.angleDeg)}°", "기울어짐", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Fact("${a.stats.seconds.roundToInt()}초", "걸린 시간", Modifier.weight(1f))
                Fact("${one(a.stats.distance)} m", "움직인 거리", Modifier.weight(1f))
                Fact(Texts.centimetres(a.lengthways), "앞뒤로 벗어남", Modifier.weight(1f))
            }
            if (first != null) {
                Spacer(Modifier.height(14.dp))
                Label("처음: ${Texts.stats(first)}  →  이번: 전후진 ${a.stats.moves}번")
            }
            if (a.cones.size == 2) {
                // The one number here that can be checked against the real world: what the mirrors showed.
                Spacer(Modifier.height(10.dp))
                Label("콘과 가장 가까웠을 때: 왼쪽 ${Texts.centimetres(a.cones[0])} · 오른쪽 ${Texts.centimetres(a.cones[1])}. 거울로 본 간격과 비교해 보세요.")
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().glass(26).padding(22.dp)) {
                Text("코치", color = Palette.Accent, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                for (advice in e.advice) {
                    Spacer(Modifier.height(6.dp))
                    Text(Texts.advice(advice), color = Palette.Text, fontSize = 23.sp, lineHeight = 32.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            // Once a lesson's test is passed, the way on is the next lesson.
            val lesson = e.lot?.config?.id
            val next = if (s.level == Level.OPEN && lesson != null && lesson in s.passed) Course.after(lesson)?.let { Scenarios.byId(it) } else null
            if (next != null) {
                BigButton("다음 단계 · ${next.name}", { core.nextLesson(next) }, Modifier.fillMaxWidth(), color = Palette.Go)
                Spacer(Modifier.height(12.dp))
            }
            BigButton("다시 연습", { core.again() }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuietButton("움직임 다시 보기", { core.showReplay(a.samples, "방금 주차한 길") })
                if (s.level == Level.REAL && a.success) QuietButton("제대로 섰어요 · 위치 맞춤", { core.reanchor() })
                if (s.level == Level.OPEN && a.success) QuietButton("랜덤 환경으로", { core.randomLot() })
                QuietButton("다른 환경", { core.open(if (s.level == Level.OPEN) Screen.SCENARIOS else Screen.HOME) })
            }
        }
        OrbitScene(Modifier.weight(1.2f).fillMaxHeight().clip(RoundedCornerShape(34.dp))) { tilt, spin ->
            SceneData(
                s.spec, e.pose, e.wheelDeg, e.lot,
                trail = a.samples,
                ghost = e.baseline ?: e.history.firstOrNull()?.takeIf { e.history.size > 1 }?.samples ?: emptyList(),
                hit = e.hit, tilt = tilt, spin = spin,
            )
        }
    }
}

/** The pose at time [t] along a recorded drive, sliding smoothly between the stored points. */
private fun poseAt(samples: List<Sample>, t: Double): Pair<Pose, Double> {
    val i = samples.indexOfFirst { it.t >= t }
    if (i <= 0) {
        val s = if (i == 0) samples.first() else samples.last()
        return s.pose to s.wheelDeg
    }
    val a = samples[i - 1]
    val b = samples[i]
    val k = if (b.t > a.t) (t - a.t) / (b.t - a.t) else 1.0
    val pose = Pose(
        a.pose.x + (b.pose.x - a.pose.x) * k,
        a.pose.y + (b.pose.y - a.pose.y) * k,
        a.pose.h + wrap(b.pose.h - a.pose.h) * k,
    )
    return pose to a.wheelDeg + (b.wheelDeg - a.wheelDeg) * k
}

@Composable
fun ReplayScreen(s: UiState, core: AppCore) {
    val samples = s.replay
    if (samples == null || samples.isEmpty()) {
        ScreenFrame("다시 보기", { core.back() }) { Body("기록이 없어요.") }
        return
    }
    val (pose, wheel) = poseAt(samples, s.replayT)
    val shown = samples.filter { it.t <= s.replayT }
    Box(Modifier.fillMaxSize()) {
        OrbitScene(Modifier.fillMaxSize()) { tilt, spin ->
            SceneData(s.spec, pose, wheel, core.engine.lot, trail = shown, ghost = samples, tilt = tilt, spin = spin, frame = s.frame)
        }
        Column(Modifier.align(Alignment.TopStart).padding(24.dp)) {
            Text(s.replayTitle, color = Palette.Text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(Texts.stats(DriveStats.of(samples)), color = Palette.Dim, fontSize = 26.sp)
            Label("파란 선은 전진, 주황 선은 후진 · 4배속")
        }
        BigButton(
            if (s.level == Level.REAL && core.engine.lot == null) "다음: 주변 알려주기" else "닫기",
            { core.closeReplay() },
            Modifier.align(Alignment.BottomEnd).padding(24.dp),
        )
    }
}

// ---------------------------------------------------------------------- The car's size

@Composable
fun GarageScreen(s: UiState, core: AppCore) {
    val spec = s.spec
    ScreenFrame("내 차", { core.back() }) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Label(
                    if (s.specFromCar) {
                        "차가 알려 준 크기를 쓰고 있어요. 따로 고를 필요가 없습니다."
                    } else {
                        "차가 크기를 알려 주면 자동으로 맞춰집니다. 알려 주지 않는 차는 비슷한 크기를 한 번만 고르세요."
                    },
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (preset in Vehicles.presets) Chip(preset.name, preset.id == spec.id) { core.setSpec(preset) }
                }
                Spacer(Modifier.height(18.dp))
                Label("핸들을 끝까지 돌렸을 때  ${spec.lockDeg.roundToInt()}° (약 ${one(spec.lockDeg / 360)}바퀴)")
                Slider(
                    value = spec.lockDeg.toFloat(),
                    onValueChange = { core.setSpec(spec.copy(lockDeg = (it / 10).roundToInt() * 10.0)) },
                    valueRange = 360f..720f,
                )
                Label("조향비  ${one(spec.steeringRatio)} : 1")
                Slider(
                    value = spec.steeringRatio.toFloat(),
                    onValueChange = { core.setSpec(spec.copy(steeringRatio = (it * 10).roundToInt() / 10.0)) },
                    valueRange = 11f..20f,
                )
                Label("화면의 차가 실제보다 덜 돌면 조향비를 낮추고, 더 돌면 높이세요.")
                Spacer(Modifier.height(14.dp))
                QuietButton("차가 알려 준 값으로 되돌리기", { core.useCarSpec() })
            }
            Panel(Modifier.weight(1f).fillMaxHeight()) {
                Text(spec.name, color = Palette.Text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Body("길이 ${one(spec.length)} m\n폭 ${one(spec.width)} m\n축거 ${one(spec.wheelbase)} m")
                Body("최소 회전 반경 ${one(spec.minRadius)} m", color = Palette.Go)
                Spacer(Modifier.weight(1f))
                var demo by remember { mutableStateOf(core.forceDemo) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("데모 차량 사용", color = Palette.Text, fontSize = 24.sp)
                        Label("차량 신호 대신 화면 조작으로 시연합니다. 다음 실행부터.")
                    }
                    Switch(checked = demo, onCheckedChange = {
                        demo = it
                        core.setForceDemo(it)
                    })
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- Demo car controls

/** Shown only when there is no real car: a gear lever, a speed lever and a steering wheel. */
@Composable
fun DemoPanel(s: UiState, core: AppCore) {
    var speed by remember { mutableStateOf(0f) }
    var wheel by remember { mutableStateOf(0f) }
    val lock = s.spec.lockDeg.toFloat()
    Row(
        Modifier.fillMaxWidth().background(Palette.Panel).padding(horizontal = 22.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("데모 차량", color = Palette.Dim, fontSize = 18.sp)
        for ((g, label) in listOf(Gear.PARK to "P", Gear.REVERSE to "R", Gear.DRIVE to "D")) {
            Chip(label, s.gear == g) {
                speed = 0f
                core.demoSpeed(0.0)
                core.demoGear(g)
            }
        }
        Column(Modifier.weight(1f)) {
            Text("속도 ${one(speed * 3.6)} km/h", color = Palette.Dim, fontSize = 16.sp)
            Slider(value = speed, onValueChange = {
                speed = it
                core.demoSpeed(it.toDouble())
            }, valueRange = 0f..2.5f)
        }
        Chip("정지", false) {
            speed = 0f
            core.demoSpeed(0.0)
        }
        Column(Modifier.weight(1.6f)) {
            Text("핸들 ${if (abs(wheel) < 1f) "가운데" else Texts.side(wheel.toDouble()) + " " + abs(wheel).roundToInt() + "°"}", color = Palette.Dim, fontSize = 16.sp)
            Slider(value = wheel, onValueChange = {
                wheel = it
                core.demoWheel(it.toDouble())
            }, valueRange = -lock..lock)
        }
        Chip("가운데", false) {
            wheel = 0f
            core.demoWheel(0.0)
        }
        // To see practice wait while a door is open.
        Chip(if (s.doorOpen) "문 닫기" else "문 열기", s.doorOpen) { core.demoDoor(!s.doorOpen) }
    }
}
