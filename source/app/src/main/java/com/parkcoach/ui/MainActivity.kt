package com.parkcoach.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import kotlin.math.min
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.BoxWithConstraints
import com.parkcoach.App

/**
 * The only activity. It is declared distractionOptimized because its whole purpose is a low-speed
 * manoeuvre: the picture only mirrors what the car itself is doing, and guidance stops above 15 km/h.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = (application as App).core
        // Every car signal is a permission. Only the car's speed is one the driver is asked about,
        // and Android Automotive words that question as "location", so the app says first what it
        // is for. Listeners registered before the answer stay silent for good, so the car is
        // connected only once the answer is in: straight away when there is nothing left to ask.
        val missing = AppCore.CAR_PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        // On a phone or tablet there is no such permission to give: the demo car needs none.
        val toAsk = AppCore.SPEED_PERMISSION in missing && packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        core.askForCar = {
            try {
                requestPermissions(missing.toTypedArray(), CAR_REQUEST)
            } catch (e: Exception) {
                // Nothing to ask on this device, a phone or tablet: the demo car needs none.
                core.reconnect()
            }
        }
        core.start(connectNow = !toAsk)
        if (toAsk) core.explainCarAsk()
        // Saving a place to practise at needs to know where the car is; asked only when that is used.
        core.askForLocation = {
            try {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_REQUEST)
            } catch (e: Exception) {
                // Nothing to ask on this device.
            }
        }
        // The small window that stays up over the rear camera needs the driver's leave to draw
        // over other apps. That is given on a page of the system's settings, where there is one.
        core.askForOverlay = {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                true
            } catch (e: Exception) {
                false
            }
        }
        core.locate = { area() }
        setContent {
            ParkCoachTheme {
                Root(core)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as App).core.shown()
    }

    /**
     * Something else is taking the screen: in reverse, the car's rear camera. The app says where on
     * the screen it was, so that its small window can go in a corner of the same area.
     */
    override fun onPause() {
        (application as App).core.hidden(area())
        super.onPause()
    }

    /** The part of the screen the app is drawn in, in pixels. */
    private fun area(): Rect {
        val root = window.decorView
        val at = IntArray(2)
        root.getLocationOnScreen(at)
        val area = Rect(at[0], at[1], at[0] + root.width, at[1] + root.height)
        // Less whatever bars the system draws over the edges.
        @Suppress("DEPRECATION")
        root.rootWindowInsets?.let {
            area.left += it.systemWindowInsetLeft
            area.top += it.systemWindowInsetTop
            area.right -= it.systemWindowInsetRight
            area.bottom -= it.systemWindowInsetBottom
        }
        return area
    }

    override fun onDestroy() {
        (application as App).core.askForLocation = null
        (application as App).core.askForCar = null
        (application as App).core.askForOverlay = null
        (application as App).core.locate = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Granted or not, this is the moment to connect: whatever was refused shows as a signal
        // that cannot be read, never as a zero.
        if (requestCode == CAR_REQUEST) (application as App).core.reconnect()
    }

    private companion object {
        const val CAR_REQUEST = 1
        const val LOCATION_REQUEST = 2
    }
}

/** Height in dp of the area the app draws in, once it has been fitted. */
val LocalStageHeight = compositionLocalOf { DESIGN_HEIGHT }

/** The screens are laid out for an area of at least this many dp. */
private const val DESIGN_WIDTH = 1920
private const val DESIGN_HEIGHT = 1080

/**
 * A car display reports a density of its own, and often one that leaves far more dp than the
 * screens were laid out for, so that everything comes out too small to read at a glance from the
 * driver's seat. The whole app is therefore drawn at the density that gives it its design area,
 * whatever the display says: the same picture on the Pleos display, a tablet and the desk.
 */
@Composable
private fun Fitted(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = constraints.maxWidth
        val high = constraints.maxHeight
        val fit = min(wide / DESIGN_WIDTH.toFloat(), high / DESIGN_HEIGHT.toFloat())
        if (fit <= 0f) {
            content()
        } else {
            CompositionLocalProvider(
                LocalDensity provides Density(fit, 1f),
                LocalStageHeight provides (high / fit).toInt(),
                content = content,
            )
        }
    }
}

@Composable
fun Root(core: AppCore) {
    val s = core.state

    // One step of the trainer per display frame, whatever screen is showing.
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { core.onFrame(it) }
        }
    }
    BackHandler(enabled = s.screen != Screen.HOME) { core.back() }

    Surface(color = Palette.Bg, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            Fitted {
                Stage(s, core)
                if (s.carAsk) CarAsk(core)
            }
        }
    }
}

@Composable
private fun Stage(s: UiState, core: AppCore) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (s.screen) {
                Screen.HOME -> HomeScreen(s, core)
                Screen.SCENARIOS -> ScenariosScreen(s, core)
                Screen.EDITOR -> EditorScreen(s, core)
                Screen.DRIVE -> DriveScreen(s, core)
                Screen.RESULT -> ResultScreen(s, core)
                Screen.REPLAY -> ReplayScreen(s, core)
                Screen.GARAGE -> GarageScreen(s, core)
                Screen.PLACES -> PlacesScreen(s, core)
                Screen.SIGNALS -> SignalsScreen(s, core)
                Screen.SETTINGS -> SettingsScreen(s, core)
            }
        }
        if (s.demo && (s.screen == Screen.DRIVE)) DemoPanel(s, core)
    }
}
