package com.parkcoach

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import com.parkcoach.ui.AppCore
import com.parkcoach.ui.Sprites

class App : Application() {
    val core: AppCore by lazy { AppCore(this) }

    override fun onCreate() {
        super.onCreate()
        // The car pictures are in assets/cars. A sheet that is missing only means that car is drawn the old way.
        Sprites.loader = { name -> assets.open("cars/$name.webp").use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }
    }
}
