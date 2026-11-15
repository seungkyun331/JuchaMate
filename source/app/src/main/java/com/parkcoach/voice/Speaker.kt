package com.parkcoach.voice

import android.content.Context
import android.util.Log
import java.util.Locale

/** Spoken instructions, so the driver does not have to read during the check. */
interface Speaker {
    fun speak(text: String)

    fun stop()

    fun release()
}

object SilentSpeaker : Speaker {
    override fun speak(text: String) = Unit

    override fun stop() = Unit

    override fun release() = Unit
}

/**
 * The car's own voice through the Pleos TextToSpeech SDK, in on-device mode: no network and no
 * app registration needed. The SDK is called by name, so a version whose calls differ costs only
 * the car's voice: Android's own speech engine then speaks instead, and the driver is never left
 * without one.
 */
class PleosSpeaker(context: Context) : Speaker {
    private val fallback: Speaker = try {
        AndroidSpeaker(context)
    } catch (e: Throwable) {
        SilentSpeaker
    }
    private var tts: Any? = null
    private var speakMethod: java.lang.reflect.Method? = null

    @Volatile
    private var ready = false

    init {
        try {
            val type = Class.forName("ai.pleos.playground.tts.TextToSpeech")
            val made = make(type, context.applicationContext)
            tts = made
            // 3.3.1 has only speak(text, isFinal); older versions may have speak(text).
            speakMethod = type.methods
                .filter { it.name == "speak" && it.parameterTypes.firstOrNull() == String::class.java }
                .filter { it.parameterCount == 1 || (it.parameterCount == 2 && it.parameterTypes[1] == java.lang.Boolean.TYPE) }
                .maxByOrNull { it.parameterCount }
            // The form with a listener says when the service is up; the bare form gives no word at all.
            val starts = type.methods.filter { it.name == "initialize" }
            val start = starts.firstOrNull { m -> m.parameterCount > 0 && m.parameterTypes.all { it.isInterface } }
                ?: starts.minByOrNull { it.parameterCount }
            when {
                made == null -> Unit
                start == null -> ready = true
                start.parameterCount == 0 -> {
                    start.invoke(made)
                    ready = true
                }
                else -> start.invoke(made, *start.parameterTypes.map { if (it.isInterface) listener(it) else null }.toTypedArray())
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Pleos TTS is not usable here: $e")
        }
    }

    /** `TextToSpeech(context, Mode.ON_DEVICE)`, or whatever shorter form this version has. */
    private fun make(type: Class<*>, context: Context): Any? {
        for (maker in type.constructors.sortedByDescending { it.parameterCount }) {
            val takes = maker.parameterTypes
            if (takes.isEmpty() || !takes[0].isAssignableFrom(context.javaClass)) continue
            if (takes.size == 1) return maker.newInstance(context)
            if (takes.size == 2 && takes[1].isEnum) {
                val modes = takes[1].enumConstants ?: continue
                val mode = modes.firstOrNull { (it as Enum<*>).name == "ON_DEVICE" } ?: modes.firstOrNull() ?: continue
                return maker.newInstance(context, mode)
            }
        }
        return null
    }

    /** Stands in for the SDK's start-up listener: `onSuccess` means it can speak. */
    private fun listener(type: Class<*>): Any = java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, given ->
        when (method.name) {
            "equals" -> proxy === given?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "ParkCoach(${type.simpleName})"
            else -> {
                if (method.name == "onSuccess") ready = true else Log.w(TAG, "Pleos TTS ${method.name}: ${given?.firstOrNull()}")
                if (method.returnType == java.lang.Boolean.TYPE) false else null
            }
        }
    }

    override fun speak(text: String) {
        val say = speakMethod
        if (ready && say != null) {
            try {
                // isFinal = true: the whole sentence, not one piece of a stream.
                if (say.parameterCount == 2) say.invoke(tts, text, true) else say.invoke(tts, text)
                return
            } catch (e: Throwable) {
                Log.w(TAG, "Pleos TTS speak failed: $e")
                ready = false
            }
        }
        fallback.speak(text)
    }

    override fun stop() {
        call("stop")
        fallback.stop()
    }

    override fun release() {
        call("release")
        ready = false
        fallback.release()
    }

    private fun call(name: String) {
        val t = tts ?: return
        try {
            t.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(t)
        } catch (e: Throwable) {
            Log.w(TAG, "Pleos TTS $name failed: $e")
        }
    }

    private companion object {
        const val TAG = "ParkCoach"
    }
}

/** Android's built-in speech engine, used when the app runs outside a Pleos car. */
class AndroidSpeaker(context: Context) : Speaker {
    private var ready = false
    private var tts: android.speech.tts.TextToSpeech? = null

    init {
        tts = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
            ready = status == android.speech.tts.TextToSpeech.SUCCESS
            if (ready) {
                try {
                    tts?.setLanguage(Locale.KOREAN)
                } catch (e: Exception) {
                    ready = false
                }
            }
        }
    }

    override fun speak(text: String) {
        if (ready) tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "parkcoach")
    }

    override fun stop() {
        if (ready) tts?.stop()
    }

    override fun release() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}
