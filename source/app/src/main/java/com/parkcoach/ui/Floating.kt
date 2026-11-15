package com.parkcoach.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.parkcoach.core.Phase
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A small window that stays on top of whatever has the screen.
 *
 * In reverse the car puts its rear camera where the app was, and the app has no say in that. The
 * voice carries on regardless. For a driver who has let the app draw over other apps, this window
 * keeps the instruction, the wheel and a small map in one corner of the camera picture as well. It
 * is small on purpose: the camera is the picture that matters while backing up.
 *
 * Drawing over other apps is a permission the driver gives in the system's settings. Without it
 * nothing is shown and nothing else changes.
 */
class FloatingWindow(private val app: Context, private val core: AppCore) {

    private val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: ComposeView? = null
    private var owner: Owner? = null
    private var params: WindowManager.LayoutParams? = null
    private var area = Rect()
    private var scale = 1f

    /** Which of the sizes it is at. */
    var size = 1
        private set

    /** The window just taken down, still there but unseen until its surface can go quietly. */
    private var leaving: ComposeView? = null
    private var leavingLife: Owner? = null

    /**
     * Which top corner of the app's area it sits in. The far side first: the Pleos camera keeps its
     * own buttons down the near edge, and those must stay free.
     */
    private var onLeft = false

    private var lastWhy = ""

    /** How far off the system put the window from where it was asked for, found once it is up. */
    private var shiftX = 0
    private var shiftY = 0

    val showing: Boolean get() = view != null

    /** The driver has allowed the app to draw over other apps. */
    val allowed: Boolean
        get() = try {
            Settings.canDrawOverlays(app)
        } catch (e: Exception) {
            false
        }

    /** Puts the window up in a corner of [within], the part of the screen the app had, in pixels. */
    fun show(within: Rect): Boolean {
        if (view != null) return true
        if (!allowed || within.width() <= 0 || within.height() <= 0) {
            // Asked again every second while it is wanted, so said only when the reason changes.
            val why = "allowed=$allowed area=$within"
            if (why != lastWhy) Log.i(TAG, "Small window not shown: $why")
            lastWhy = why
            return false
        }
        lastWhy = ""
        area = Rect(within)
        // One window at a time: the one on its way out goes now.
        leaving?.let { remove(it, leavingLife) }
        // The same share of the app's area on any display.
        scale = (area.width() * SHARES[size] / PANEL_WIDTH).coerceIn(0.5f, 4f)
        shiftX = 0
        shiftY = 0

        val made = ComposeView(app)
        val life = Owner()
        made.setViewTreeLifecycleOwner(life)
        made.setViewTreeSavedStateRegistryOwner(life)
        val drawnAt = scale
        made.setContent { FloatingPanel(core, drawnAt) }

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // It never takes the keyboard or the back key from whatever is underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.LEFT
        // Placed in screen pixels, so the system's bars must not move it.
        if (Build.VERSION.SDK_INT >= 30) p.fitInsetsTypes = 0
        p.title = Texts.APP
        place(p)
        return try {
            life.start()
            manager.addView(made, p)
            view = made
            owner = life
            params = p
            Log.i(TAG, "Small window added: area=$area at ${p.x},${p.y} scale=$scale")
            // Whether the system goes on showing it is the system's call; this says when that changes.
            if (Build.VERSION.SDK_INT >= 34) {
                made.viewTreeObserver.addOnWindowVisibilityChangeListener { visibility -> Log.i(TAG, "Small window visibility=$visibility") }
            }
            made.post { settle() }
            true
        } catch (e: Exception) {
            Log.w(TAG, "The small window could not be shown: ${e.message}")
            life.stop()
            false
        }
    }

    /**
     * Takes the window down. It is made unseen and untouchable at once, and its surface is given up
     * a moment later: the camera closing and the app coming back are already a lot of surfaces
     * changing hands in the same instant.
     */
    fun hide() {
        val shown = view ?: return
        val p = params
        val life = owner
        view = null
        owner = null
        params = null
        shown.alpha = 0f
        if (p != null) {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try {
                manager.updateViewLayout(shown, p)
            } catch (e: Exception) {
                Log.d(TAG, "Quietening the small window failed: ${e.message}")
            }
        }
        leaving = shown
        leavingLife = life
        shown.postDelayed({ remove(shown, life) }, LINGER_MS)
    }

    private fun remove(gone: ComposeView, life: Owner?) {
        if (leaving !== gone) return
        leaving = null
        leavingLife = null
        try {
            manager.removeView(gone)
        } catch (e: Exception) {
            Log.d(TAG, "Removing the small window failed: ${e.message}")
        }
        life?.stop()
    }

    /** Small, medium, large and round again. Returns which, to be remembered. */
    fun resize(to: Int): Int {
        size = ((to % SHARES.size) + SHARES.size) % SHARES.size
        if (view != null) {
            val within = Rect(area)
            hide()
            show(within)
        }
        return size
    }

    /** To the other top corner, for when it is in the way of something. */
    fun move() {
        val shown = view ?: return
        val p = params ?: return
        onLeft = !onLeft
        place(p)
        try {
            manager.updateViewLayout(shown, p)
        } catch (e: Exception) {
            Log.d(TAG, "Moving the small window failed: ${e.message}")
        }
    }

    private fun place(p: WindowManager.LayoutParams) {
        val wide = (PANEL_WIDTH * scale).roundToInt()
        val margin = (MARGIN * scale).roundToInt()
        p.x = (if (onLeft) area.left + margin else area.right - wide - margin) + shiftX
        p.y = area.top + margin + shiftY
    }

    /** A display with bars or panels of its own may count from somewhere else: look, and correct once. */
    private fun settle() {
        val shown = view ?: return
        val p = params ?: return
        val at = IntArray(2)
        shown.getLocationOnScreen(at)
        report("first layout")
        val dx = p.x - at[0]
        val dy = p.y - at[1]
        if (abs(dx) <= 2 && abs(dy) <= 2) return
        shiftX = dx
        shiftY = dy
        place(p)
        try {
            manager.updateViewLayout(shown, p)
        } catch (e: Exception) {
            Log.d(TAG, "Placing the small window failed: ${e.message}")
        }
    }

    /** Where the window really is and whether the system is showing it, for whoever is testing. */
    private fun report(whenAsked: String) {
        val shown = view ?: return
        val at = IntArray(2)
        shown.getLocationOnScreen(at)
        Log.i(TAG, "Small window $whenAsked: at ${at[0]},${at[1]} size ${shown.width}x${shown.height} windowVisibility=${shown.windowVisibility} isShown=${shown.isShown}")
    }

    /**
     * A window outside any activity has nobody to tell it that it is alive, and Compose draws
     * nothing without that. This stands in: alive from when the window goes up until it comes down.
     */
    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

        fun start() {
            saved.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun stop() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }

    private companion object {
        const val TAG = "ParkCoach"

        /** The widths it can have, as a share of the width the app had. */
        val SHARES = floatArrayOf(0.30f, 0.42f, 0.55f)
        const val MARGIN = 20

        /** How long a window taken down keeps its surface, unseen. */
        const val LINGER_MS = 1500L
    }
}

/** Width in dp the small window is laid out for. */
private const val PANEL_WIDTH = 460
private const val GAUGE = 190

/**
 * What the small window shows: what to do now, the wheel, and the whole situation from above.
 * Pressing it moves it to the other corner; the cross puts it away until the app is next opened.
 */
@Composable
private fun FloatingPanel(core: AppCore, scale: Float) {
    CompositionLocalProvider(LocalDensity provides Density(scale, 1f)) {
        ParkCoachTheme {
            val s = core.state
            val e = core.engine
            val read = Readout(s, e)
            val beat = ((core.clock % TURN_BEAT) / TURN_BEAT).toFloat()
            Column(
                Modifier
                    .width(PANEL_WIDTH.dp)
                    .clip(RoundedCornerShape(34.dp))
                    .background(Palette.Bg.copy(alpha = 0.94f))
                    .clickable { core.moveFloating() }
                    .padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Texts.APP, color = Palette.Text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(10.dp))
                    Text(Texts.FLOATING_MOVE, color = Palette.Dim, fontSize = 15.sp, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.height(46.dp).clip(RoundedCornerShape(50)).background(Palette.Soft).clickable { core.resizeFloating() }.padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(Texts.FLOATING_SIZE, color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(46.dp).clip(CircleShape).background(Palette.Soft).clickable { core.closeFloating() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("×", color = Palette.Text, fontSize = 26.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                val done = e.last
                if (s.screen == Screen.RESULT && done != null) {
                    // The practice ended with the camera still up: say how, and leave the rest for the full screen.
                    Text(
                        Texts.verdict(done),
                        color = if (done.success) Palette.Go else Palette.Slow,
                        fontSize = 36.sp,
                        lineHeight = 46.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(Texts.FLOATING_DONE, color = Palette.Dim, fontSize = 20.sp, lineHeight = 28.sp)
                } else {
                    NowCard(s, e, read.hint)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    WheelGauge(e, read, GAUGE, beat)
                    val showGhost = s.phase == Phase.PARKING || s.phase == Phase.LEAVING || s.phase == Phase.READY
                    SceneView(
                        SceneData(
                            spec = s.spec,
                            pose = e.pose,
                            wheelDeg = e.wheelDeg,
                            lot = e.lot,
                            trail = e.trail,
                            ghost = if (showGhost) e.baseline ?: e.history.firstOrNull()?.samples ?: emptyList() else emptyList(),
                            path = if (s.assist == Assist.SOLO) emptyList() else read.hint?.path ?: emptyList(),
                            box = if (s.assist == Assist.SOLO) null else read.hint?.box,
                            hit = e.hit,
                            frame = s.frame,
                            limit = e.limit,
                        ),
                        Modifier.weight(1f).height(GAUGE.dp).clip(RoundedCornerShape(26.dp)),
                    )
                }
            }
        }
    }
}
