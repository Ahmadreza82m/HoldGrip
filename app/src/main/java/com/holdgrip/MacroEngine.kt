package com.holdgrip

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class MacroAction { TAP, LONG_PRESS, WAIT }

data class MacroStep(
    val action: MacroAction,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val durationMs: Long = 60L,
    val delayAfterMs: Long = 100L
)

data class MacroDefinition(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val steps: List<MacroStep>,
    val repeatCount: Int = 1
)

/** Local, offline storage for user-created macros. */
class MacroRepository(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("holdgrip_macros", android.content.Context.MODE_PRIVATE)

    fun all(): List<MacroDefinition> {
        val source = prefs.getString("items", "[]") ?: "[]"
        return try {
            val array = JSONArray(source)
            (0 until array.length()).mapNotNull { index ->
                runCatching { decode(array.getJSONObject(index)) }.getOrNull()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(macro: MacroDefinition) {
        val items = all().filterNot { it.id == macro.id } + macro
        write(items)
    }

    fun delete(id: String) = write(all().filterNot { it.id == id })

    private fun write(items: List<MacroDefinition>) {
        val array = JSONArray()
        items.forEach { macro ->
            val steps = JSONArray()
            macro.steps.forEach { step ->
                steps.put(JSONObject().apply {
                    put("action", step.action.name)
                    put("x", step.x.toDouble())
                    put("y", step.y.toDouble())
                    put("durationMs", step.durationMs)
                    put("delayAfterMs", step.delayAfterMs)
                })
            }
            array.put(JSONObject().apply {
                put("id", macro.id)
                put("name", macro.name)
                put("repeatCount", macro.repeatCount)
                put("steps", steps)
            })
        }
        prefs.edit().putString("items", array.toString()).apply()
    }

    private fun decode(item: JSONObject): MacroDefinition {
        val stepsJson = item.getJSONArray("steps")
        val steps = (0 until stepsJson.length()).map { index ->
            val step = stepsJson.getJSONObject(index)
            MacroStep(
                action = MacroAction.valueOf(step.getString("action")),
                x = step.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                y = step.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f),
                durationMs = step.optLong("durationMs", 60L).coerceIn(1L, 5000L),
                delayAfterMs = step.optLong("delayAfterMs", 100L).coerceIn(0L, 60000L)
            )
        }
        return MacroDefinition(
            id = item.getString("id"),
            name = item.getString("name"),
            steps = steps,
            repeatCount = item.optInt("repeatCount", 1).coerceIn(1, 99)
        )
    }
}

/**
 * Executes one macro at a time. It never dispatches while Hold-Grip is active.
 * A running Hold-Grip gesture takes priority; a macro waits briefly for it to finish.
 */
class MacroRunner(
    private val service: AccessService,
    private val onState: (String) -> Unit
) {
    companion object {
        @Volatile private var activeRunner: MacroRunner? = null
        fun stopActive() { activeRunner?.stop() }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var macro: MacroDefinition? = null
    private var stepIndex = 0
    private var pass = 0
    private var busyWaits = 0
    private var generation = 0L
    var isRunning: Boolean = false
        private set

    fun start(definition: MacroDefinition): Boolean {
        if (isRunning || definition.steps.isEmpty()) return false
        generation++
        macro = definition
        stepIndex = 0
        pass = 0
        busyWaits = 0
        isRunning = true
        activeRunner = this
        onState("در حال اجرا: ${definition.name}")
        scheduleNext(generation, 0L)
        return true
    }

    /** Stops after the currently dispatched Android gesture completes. */
    fun stop() {
        if (!isRunning) return
        generation++
        handler.removeCallbacksAndMessages(null)
        isRunning = false
        macro = null
        onState("متوقف شد؛ حرکت جاری در صورت اجرا کامل می‌شود")
    }

    private fun scheduleNext(token: Long, delayMs: Long) {
        handler.postDelayed({
            if (!isCurrent(token)) return@postDelayed
            advance(token)
        }, delayMs.coerceAtLeast(0L))
    }

    private fun advance(token: Long) {
        if (!isCurrent(token)) return
        val current = macro ?: return finish("پایان")
        if (service.isHoldGripBusy()) {
            busyWaits++
            if (busyWaits > 200) return finish("توقف: Hold-Grip بیش از ۱۰ ثانیه فعال بود")
            scheduleNext(token, 50L)
            return
        }
        busyWaits = 0

        if (stepIndex >= current.steps.size) {
            pass++
            if (pass >= current.repeatCount) return finish("با موفقیت تمام شد")
            stepIndex = 0
            scheduleNext(token, 0L)
            return
        }

        val step = current.steps[stepIndex]
        if (step.action == MacroAction.WAIT) {
            stepIndex++
            scheduleNext(token, step.durationMs)
            return
        }

        val metrics = service.resources.displayMetrics
        val x = (step.x.coerceIn(0f, 1f) * metrics.widthPixels).coerceIn(0f, (metrics.widthPixels - 1).coerceAtLeast(0).toFloat())
        val y = (step.y.coerceIn(0f, 1f) * metrics.heightPixels).coerceIn(0f, (metrics.heightPixels - 1).coerceAtLeast(0).toFloat())
        val path = Path().apply { moveTo(x, y) }
        val duration = if (step.action == MacroAction.TAP) step.durationMs.coerceIn(1L, 150L) else step.durationMs.coerceIn(100L, 5000L)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()

        val accepted = service.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (!isCurrent(token)) return
                stepIndex++
                scheduleNext(token, step.delayAfterMs)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (isCurrent(token)) finish("توقف شد: سیستم ژست را لغو کرد")
            }
        }, handler)

        if (!accepted) finish("توقف: اندروید اجرای این لمس را نپذیرفت")
    }

    private fun isCurrent(token: Long) = isRunning && token == generation

    private fun finish(message: String) {
        generation++
        handler.removeCallbacksAndMessages(null)
        isRunning = false
        if (activeRunner === this) activeRunner = null
        macro = null
        onState(message)
    }
}
