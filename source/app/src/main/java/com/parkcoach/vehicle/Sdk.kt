package com.parkcoach.vehicle

import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * Calls the Pleos SDKs by name instead of by type.
 *
 * The SDK's method names are published, but its exact signatures differ between versions, and some
 * of its getters cannot be seen from Kotlin at all. Called by name, a method that has moved costs
 * the app that one signal, which the signal page then reports, and never the whole build.
 * It is also why the same app starts on a plain Android Automotive emulator with no Pleos SDK.
 */
internal object Sdk {
    private const val TAG = "ParkCoach"

    fun type(name: String): Class<*>? = try {
        Class.forName(name)
    } catch (e: Throwable) {
        null
    }

    private fun methods(target: Any, name: String): List<Method> {
        val open = target.javaClass.methods.filter { it.name == name && !Modifier.isStatic(it.modifiers) }
        if (open.isNotEmpty()) return open
        // Declared but not public: still callable once opened.
        return target.javaClass.declaredMethods.filter { it.name == name && !Modifier.isStatic(it.modifiers) }.onEach {
            try {
                it.isAccessible = true
            } catch (e: Throwable) {
                Log.d(TAG, "$name cannot be opened: $e")
            }
        }
    }

    fun has(target: Any?, name: String): Boolean = target != null && methods(target, name).isNotEmpty()

    /** Names of the methods of [target] that start with [prefix]. */
    fun named(target: Any?, prefix: String): List<String> =
        target?.javaClass?.methods?.map { it.name }?.filter { it.startsWith(prefix) && !it.contains('$') }?.distinct() ?: emptyList()

    private fun cause(e: Throwable): Throwable = (e as? InvocationTargetException)?.targetException ?: e

    /** What went wrong, in the SDK's own words: its error codes arrive as the exception's message. */
    fun reason(e: Any?): String = when (e) {
        is SecurityException -> "권한 없음"
        is Throwable -> e.message ?: e.javaClass.simpleName
        null -> "실패"
        else -> e.toString()
    }

    /** Calls a method that takes exactly [args] and gives back what it returns. Null when there is no such method or it fails. */
    fun fetch(target: Any?, name: String, vararg args: Any?): Any? {
        target ?: return null
        val m = methods(target, name).firstOrNull { it.parameterCount == args.size } ?: return null
        return try {
            m.invoke(target, *args)
        } catch (e: Throwable) {
            Log.w(TAG, "$name failed: ${cause(e)}")
            null
        }
    }

    /** Calls a method for its effect. True when it ran without throwing. */
    fun run(target: Any?, name: String, vararg args: Any?): Boolean {
        target ?: return false
        val m = methods(target, name).firstOrNull { it.parameterCount == args.size } ?: return false
        return try {
            m.invoke(target, *args)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "$name failed: ${cause(e)}")
            false
        }
    }

    private fun isCallback(type: Class<*>): Boolean = Function::class.java.isAssignableFrom(type)

    /**
     * Asks for one value: `getCurrentX(area?, onComplete, onFailed)` or `checkXCapabilities(onComplete, onFailed)`.
     * The first callback parameter is the answer, the second the failure. An answer of two values
     * (a capability's settings and its bounds) arrives as a list of both.
     * False when the method is missing or could not be called; [onError] then says why.
     */
    fun ask(target: Any?, name: String, area: String?, onError: (String) -> Unit, onValue: (Any?) -> Unit): Boolean {
        if (target == null) return false
        val m = methods(target, name).maxByOrNull { it.parameterCount }
        if (m == null) {
            onError("$name 없음")
            return false
        }
        var callbacks = 0
        val args = arrayOfNulls<Any>(m.parameterCount)
        for ((k, type) in m.parameterTypes.withIndex()) {
            args[k] = when {
                isCallback(type) -> {
                    val failure = callbacks > 0
                    callbacks++
                    callback(type, if (failure) { values -> onError(reason(values.firstOrNull())) } else { values -> onValue(if (values.size == 1) values[0] else values) })
                }
                type.simpleName.endsWith("Area") -> area(type, area) ?: run {
                    onError("${type.simpleName}($area) 만들 수 없음")
                    return false
                }
                else -> null
            }
        }
        return try {
            val answer = m.invoke(target, *args)
            // A plain getter, with no callbacks, answers with what it returns.
            if (callbacks == 0) onValue(answer)
            true
        } catch (e: Throwable) {
            onError(reason(cause(e)))
            false
        }
    }

    /** A Kotlin lambda of however many parameters [type] takes. */
    private fun callback(type: Class<*>, block: (List<Any?>) -> Unit): Any? {
        val none: () -> Unit = { block(emptyList()) }
        val one: (Any?) -> Unit = { a -> block(listOf(a)) }
        val two: (Any?, Any?) -> Unit = { a, b -> block(listOf(a, b)) }
        val three: (Any?, Any?, Any?) -> Unit = { a, b, c -> block(listOf(a, b, c)) }
        return when {
            kotlin.jvm.functions.Function0::class.java.isAssignableFrom(type) -> none
            kotlin.jvm.functions.Function1::class.java.isAssignableFrom(type) -> one
            kotlin.jvm.functions.Function2::class.java.isAssignableFrom(type) -> two
            kotlin.jvm.functions.Function3::class.java.isAssignableFrom(type) -> three
            else -> null
        }
    }

    /**
     * Starts updates: `registerX(area?, listener)`. The listener is made from whatever interface the
     * method asks for, so its method names need not be known: `onFailed` is the failure, anything
     * else carries the new value first. Gives back what stops the updates, or null if they never began.
     */
    fun watch(target: Any?, register: String, unregister: String, area: String?, onError: (String) -> Unit, onValue: (Any?) -> Unit): (() -> Unit)? {
        if (target == null) return null
        val m = methods(target, register).maxByOrNull { it.parameterCount }
        if (m == null) {
            onError("$register 없음")
            return null
        }
        var listener: Any? = null
        var place: Any? = null
        val args = arrayOfNulls<Any>(m.parameterCount)
        for ((k, type) in m.parameterTypes.withIndex()) {
            args[k] = when {
                type.simpleName.endsWith("Area") -> area(type, area).also { place = it } ?: run {
                    onError("${type.simpleName}($area) 만들 수 없음")
                    return null
                }
                type.isInterface && !isCallback(type) -> listener(type, onError, onValue).also { listener = it }
                else -> null
            }
        }
        val made = listener
        if (made == null) {
            onError("$register: 리스너를 받지 않음")
            return null
        }
        return try {
            m.invoke(target, *args)
            val stop: () -> Unit = {
                // Same listener, and the same area when the call takes one.
                if (!run(target, unregister, made) && place != null) run(target, unregister, place, made)
            }
            stop
        } catch (e: Throwable) {
            onError(reason(cause(e)))
            null
        }
    }

    private fun listener(type: Class<*>, onError: (String) -> Unit, onValue: (Any?) -> Unit): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, given ->
            when (method.name) {
                "equals" -> proxy === given?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ParkCoach(${type.simpleName})"
                else -> {
                    try {
                        if (method.name == "onFailed") onError(reason(given?.firstOrNull())) else onValue(given?.firstOrNull())
                    } catch (e: Throwable) {
                        Log.w(TAG, "${type.simpleName}.${method.name}: $e")
                    }
                    if (method.returnType == java.lang.Boolean.TYPE) false else null
                }
            }
        }

    /**
     * An area such as `DoorArea(DoorValue.ROW_1_LEFT)`. Areas take `vararg values`, which is one
     * array parameter when called by name. An unknown [value] gives null, never some other door.
     */
    private fun area(type: Class<*>, value: String?): Any? {
        for (maker in type.constructors) {
            if (maker.parameterCount != 1) continue
            val taken = maker.parameterTypes[0]
            val kind = if (taken.isArray) taken.componentType else taken
            if (kind == null || !kind.isEnum) continue
            val pick = kind.enumConstants?.firstOrNull { (it as Enum<*>).name == value } ?: return null
            val given: Any = if (taken.isArray) java.lang.reflect.Array.newInstance(kind, 1).also { java.lang.reflect.Array.set(it, 0, pick) } else pick
            return try {
                maker.newInstance(given)
            } catch (e: Throwable) {
                Log.w(TAG, "${type.simpleName}($value): ${cause(e)}")
                null
            }
        }
        return null
    }

    /** A property of an SDK object by any of the names it may go by: "vin" finds getVin(), vin(), getVIN(). */
    fun property(target: Any?, vararg names: String): Any? {
        target ?: return null
        for (name in names) {
            val m = target.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && (it.name.equals("get$name", true) || it.name.equals(name, true) || it.name.equals("is$name", true))
            } ?: continue
            return try {
                m.invoke(target)
            } catch (e: Throwable) {
                null
            }
        }
        return null
    }

    fun number(x: Any?): Double? = (x as? Number)?.toDouble()

    fun name(x: Any?): String? = (x as? Enum<*>)?.name ?: x?.toString()

    /** The largest value a capability answer allows, wherever in the answer it is kept. */
    fun bound(x: Any?, depth: Int = 0): Double? {
        if (x == null || depth > 4) return null
        val high = number(property(x, "maxValue", "max", "maximum"))
        val low = number(property(x, "minValue", "min", "minimum"))
        if (high != null || low != null) return maxOf(kotlin.math.abs(high ?: 0.0), kotlin.math.abs(low ?: 0.0))
        if (x is Iterable<*>) for (e in x) bound(e, depth + 1)?.let { return it }
        if (x is Map<*, *>) for (e in x.values) bound(e, depth + 1)?.let { return it }
        if (x is Number || x is CharSequence || x is Enum<*> || x is Boolean) return null
        for (inner in listOf("areaBounds", "bounds", "areaConfig", "value")) bound(property(x, inner), depth + 1)?.let { return it }
        return null
    }
}
