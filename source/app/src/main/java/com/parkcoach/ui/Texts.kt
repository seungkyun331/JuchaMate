package com.parkcoach.ui

import com.parkcoach.core.Advice
import com.parkcoach.core.CarSize
import com.parkcoach.core.Stance
import com.parkcoach.core.Attempt
import com.parkcoach.core.Cue
import com.parkcoach.core.DriveStats
import com.parkcoach.core.Hint
import com.parkcoach.core.Kind
import com.parkcoach.core.LotConfig
import com.parkcoach.core.Opposite
import com.parkcoach.core.Side
import com.parkcoach.core.SlotType
import com.parkcoach.core.Spot
import com.parkcoach.core.Tip
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** All wording in one place. Short, plain, and the same words on screen and out loud. */
object Texts {
    const val APP = "주차메이트"
    const val TAGLINE = "내 차로, 실제 주차 칸에서, 긁힐 걱정 없이 배우는 주차"
    const val SAFETY =
        "화면의 차와 벽은 가상입니다. 이 앱은 실제 장애물과 사람을 보지 못합니다. 주변은 항상 직접 확인하세요."
    const val CREDIT =
        "차량 모델 (CC BY 4.0): “Hyundai Ioniq 5 - Lowpoly” by andikapratamaw · “2023 Hyundai Casper”, “2022 Hyundai Staria” by tonielpro520 · " +
            "“Hyundai Elantra 2022 [ Ultra Low Poly ]” by AhmedE"
    const val LEFT_TO_GO = "남음"
    const val CAR_ASK_TITLE = "차량 속도를 읽어도 될까요?"
    const val CAR_ASK =
        "차가 움직인 거리를 계산하려면 차량 속도 신호가 필요합니다. 다음에 나오는 시스템 창에는 '위치 정보'라고 적혀 있습니다. " +
            "Android Automotive가 차량 속도를 위치 권한으로 묶어 두었기 때문이며, 이 앱은 위치를 모으지 않습니다."
    const val DOOR_OPEN = "문이 열려 있습니다. 닫으면 안내를 이어갑니다"
    const val TOO_FAST = "너무 빠릅니다. 연습은 시속 15km 아래에서만 안내합니다"

    /** Read and agreed to once each time the app is opened, before any practice. */
    val CHECKS = listOf(
        "주변에 사람과 움직이는 차가 없고, 연습해도 되는 곳입니다. 주차장이라면 양옆 칸이 비어 있습니다.",
        "화면의 차와 벽은 가상입니다. 이 앱은 실제 사람과 장애물을 보지 못합니다.",
        "움직이기 전에 사이드미러와 뒤를 직접 봅니다. 차의 경고음과 후방 카메라가 이 앱보다 먼저입니다.",
        "이 앱은 차를 움직이지 않습니다. 운전은 끝까지 내가 합니다.",
    )
    const val LOOK_OUT = "거울과 주변을 보세요"
    const val FLOATING = "후진 중 작은 안내 창"
    const val FLOATING_NEEDS = "작은 안내 창은 시스템 설정에서 이 앱의 '다른 앱 위에 표시'를 허용해야 켜집니다. 꺼져 있어도 음성 안내는 계속됩니다."
    const val FLOATING_MOVE = "누르면 반대쪽으로"
    const val FLOATING_SIZE = "크기"
    const val FLOATING_DONE = "차를 세우고 기어를 P에 두면 결과가 나옵니다"
    const val CHECK_FIRST = "출발 전에 거울과 뒤를 직접 확인하세요"
    const val LIMIT = "점선은 이 차가 나가 본 곳까지입니다. 안내는 그 안에서만 합니다"

    /** What to do first, by where the practice is. */
    fun placeCar(openSpace: Boolean): String = if (openSpace) "빈 공터에 차를 세우고\n기어를 P에" else "빈 주차 칸에 차를 세우고\n기어를 P에"

    /**
     * Where the two real cones go, said in whatever the driver has to go by. In a car park that is
     * the painted lines; in an open space it is the car itself: so far to the side of the bumper's
     * ends and so far beyond it. [front] says which bumper faces the lane.
     */
    fun coneHint(aside: Double, beyond: Double, front: Boolean, openSpace: Boolean, parallel: Boolean): String = when {
        parallel -> "콘 두 개를 칸의 도로 쪽 양 모서리 바로 바깥에 세우세요"
        !openSpace -> "콘 두 개를 칸 입구 양 끝, 주차선 바로 바깥에 세우세요"
        else -> {
            val end = if (front) "앞" else "뒤"
            "콘 두 개를 $end 범퍼 양 끝에서 옆으로 ${centimetres(aside)}, ${end}쪽으로 ${centimetres(beyond)} 자리에 세우세요"
        }
    }

    fun metres(m: Double): String = String.format(Locale.US, "%.1f m", abs(m))

    fun centimetres(m: Double): String = "${(abs(m) * 100).roundToInt()} cm"

    fun turns(deg: Double): String {
        val d = abs(deg)
        return if (d >= 300) String.format(Locale.US, "%.1f바퀴", d / 360.0) else "${d.roundToInt()}°"
    }

    fun side(deg: Double): String = if (deg >= 0) "오른쪽" else "왼쪽"

    /** The big line on screen. */
    fun headline(h: Hint): String = when (h.cue) {
        Cue.FIND_SPOT -> if (h.box == null) "칸에서 조금 떨어져 멈추세요" else "초록 칸으로 이동"
        Cue.STOP -> "멈추세요"
        Cue.STEER -> "핸들을 ${side(h.turnDeg)}으로"
        Cue.SHIFT -> if (h.dir > 0) "기어를 D로" else "기어를 R로"
        Cue.MOVE -> if (h.dir > 0) "천천히 전진" else "천천히 후진"
        Cue.PARK -> "좋아요. 기어를 P로"
    }

    /** The number under it. */
    fun figure(h: Hint): String = when (h.cue) {
        Cue.FIND_SPOT -> if (h.box == null) "" else "${if (h.dir > 0) "앞으로" else "뒤로"} ${metres(h.remaining)}"
        Cue.STEER -> "${turns(h.turnDeg)} 더"
        Cue.MOVE -> metres(h.remaining)
        else -> ""
    }

    /** A second line while moving, when the wheel has drifted from where it should be. */
    fun steerNote(h: Hint): String =
        if (h.cue == Cue.MOVE && abs(h.turnDeg) > 40) "핸들 ${side(h.turnDeg)}으로 ${turns(h.turnDeg)} 더" else ""

    fun speech(h: Hint): String = when (h.cue) {
        Cue.FIND_SPOT -> if (h.box == null) "칸에서 조금 떨어져 멈추세요" else "초록 칸에서 멈추세요"
        Cue.STOP -> "멈추세요"
        Cue.STEER -> "멈춘 채로 핸들을 ${side(h.turnDeg)}으로 ${turns(h.turnDeg)} 돌리세요"
        Cue.SHIFT -> if (h.dir > 0) "기어를 디로 바꾸세요" else "기어를 알로 바꾸세요"
        // Every move starts with a look: the app sees nothing of what is really there.
        Cue.MOVE -> "${if (h.dir > 0) "앞을 확인하고 전진" else "거울과 뒤를 확인하고 후진"} ${spokenMetres(h.remaining)}"
        Cue.PARK -> "좋아요. 기어를 피로 바꾸세요"
    }

    /** Said again while the wheel is short of where it should be. */
    fun steerMore(h: Hint): String = "${side(h.turnDeg)}으로 ${turns(h.turnDeg)} 더"

    fun spokenMetres(m: Double): String =
        if (m >= 1.0) String.format(Locale.US, "%.0f미터", m) else "${(m * 10).roundToInt() * 10}센티"

    fun stats(s: DriveStats): String =
        "전후진 ${s.moves}번 · ${String.format(Locale.US, "%.0f", s.distance)} m · ${s.seconds.roundToInt()}초"

    fun verdict(a: Attempt): String = when {
        a.crashed -> "가상 장애물에 닿았어요"
        !a.success -> "칸 안에 다 들어오지 못했어요"
        a.stars == 3 -> "한 번에 완벽하게!"
        a.stars == 2 -> "잘 넣었어요"
        else -> "들어갔어요. 다음엔 덜 움직여 봐요"
    }

    fun verdictSpeech(a: Attempt): String = when {
        a.crashed -> "가상 장애물에 닿았습니다. 다시 해 봅시다."
        !a.success -> "칸 안에 다 들어오지 못했습니다."
        else -> "주차 성공. 별 ${a.stars}개."
    }

    /** One sentence from the coach about one habit. */
    fun advice(a: Advice): String = when (a.tip) {
        Tip.CLEAN -> "흠잡을 데 없는 주차였어요."
        Tip.TOUCHED -> "가상 장애물에 닿았어요. 닿기 전에 멈추고 한 번 더 빼는 편이 낫습니다."
        Tip.STARTED_LATE -> "칸을 ${metres(a.amount)} 더 지나가서 후진을 시작했어요. 초록 칸에서 멈춰 보세요."
        Tip.STARTED_EARLY -> "칸을 ${metres(a.amount)} 덜 지나서 후진을 시작했어요. 조금 더 가서 멈추세요."
        Tip.MANY_MOVES -> "전후진을 ${a.amount.roundToInt()}번 했어요. 멈춘 채로 핸들을 다 돌린 뒤 움직이면 줄어듭니다."
        Tip.LEFT_OF_CENTRE -> "칸 가운데에서 ${centimetres(a.amount)} 왼쪽으로 치우쳤어요."
        Tip.RIGHT_OF_CENTRE -> "칸 가운데에서 ${centimetres(a.amount)} 오른쪽으로 치우쳤어요."
        Tip.CROOKED -> "${a.amount.roundToInt()}° 비스듬해요. 마지막 1 m에서 핸들을 풀어 주세요."
        Tip.TOO_SHALLOW -> "${centimetres(a.amount)} 덜 들어갔어요. 앞이 통로로 나와 있습니다."
        Tip.TOO_DEEP -> "${centimetres(a.amount)} 더 들어갔어요. 뒤쪽 여유를 남기세요."
    }

    fun sizeName(size: CarSize): String = when (size) {
        CarSize.SMALL -> "경차"
        CarSize.NORMAL -> "보통"
        CarSize.LARGE -> "큰 차"
    }

    /** How the car next door was left. Along a kerb the same choices mean something slightly different. */
    fun stanceName(stance: Stance, parallel: Boolean): String = when (stance) {
        Stance.STRAIGHT -> "반듯하게"
        Stance.FORWARD -> if (parallel) "도로 쪽으로 나옴" else "앞으로 나옴"
        Stance.CLOSE -> "붙여 댐"
    }

    /** Which of a lesson's three rounds comes next. */
    fun round(a: Assist): String = when (a) {
        Assist.FULL -> "1/3 안내 받기"
        Assist.PATH -> "2/3 길만 보기"
        Assist.SOLO -> "3/3 시험"
    }

    fun assistName(a: Assist): String = when (a) {
        Assist.FULL -> "안내 전체"
        Assist.PATH -> "길만 보기"
        Assist.SOLO -> "혼자 하기"
    }

    fun hitName(kind: Kind): String = when (kind) {
        Kind.CAR -> "옆 차"
        Kind.WALL -> "벽"
        Kind.KERB -> "연석"
        Kind.CONE -> "콘"
    }

    fun typeName(t: SlotType): String = when (t) {
        SlotType.PERPENDICULAR -> "후진 주차"
        SlotType.NOSE_IN -> "전면 주차"
        SlotType.ANGLED -> "사선 주차"
        SlotType.PARALLEL -> "평행 주차"
    }

    fun sideName(s: Side): String = when (s) {
        Side.CAR -> "차"
        Side.WALL -> "벽"
        Side.EMPTY -> "빈칸"
        Side.PARTITION -> "칸막이 벽"
        Side.PILLAR -> "기둥"
    }

    fun oppositeName(o: Opposite): String = when (o) {
        Opposite.OPEN -> "트여 있음"
        Opposite.WALL -> "벽"
        Opposite.CARS -> "주차된 차"
    }

    /** A place around the space, in the driver's words. Along a kerb the neighbours are behind and ahead. */
    fun spot(spot: Spot, parallel: Boolean): String = when (spot) {
        Spot.LEFT -> if (parallel) "뒤쪽" else "운전석 쪽 옆"
        Spot.RIGHT -> if (parallel) "앞쪽" else "조수석 쪽 옆"
        Spot.OPPOSITE -> "맞은편"
    }

    fun describe(c: LotConfig): String = buildString {
        append(typeName(c.type))
        append(" · 통로 ${metres(c.aisle)}")
        if (c.type != SlotType.PARALLEL) append(" · 칸 폭 ${metres(c.room)}") else append(" · 여유 ${metres(c.room)}")
    }
}
