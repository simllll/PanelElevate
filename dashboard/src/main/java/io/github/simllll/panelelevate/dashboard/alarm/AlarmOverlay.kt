package io.github.simllll.panelelevate.dashboard.alarm

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.simllll.panelelevate.dashboard.hass.HassClient
import io.github.simllll.panelelevate.dashboard.json.asObject
import io.github.simllll.panelelevate.dashboard.json.jsonOf
import io.github.simllll.panelelevate.dashboard.json.obj
import io.github.simllll.panelelevate.dashboard.json.string
import io.github.simllll.panelelevate.dashboard.theme.HaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Broadcast from the `:dashboard` process to the service: wake the display and keep it on. */
object AlarmContract {
    const val ACTION_KEEP_AWAKE = "io.github.simllll.panelelevate.dashboard.ALARM_KEEP_AWAKE"
    const val DEFAULT_ENTITY = "alarm_control_panel.alarmanlage"
}

/**
 * Watches the alarm panel entity with its own `subscribe_entities` subscription, which (unlike
 * the card subscriptions) keeps running while the dashboard is stopped or the display is off.
 * While the alarm is `triggered` or `pending` it asks the service every [KEEP_AWAKE_MS] to wake
 * the display, so the overlay is visible even on a sleeping panel.
 */
class AlarmWatcher(private val context: Context, private val hass: HassClient, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<String?>(null)

    /** Current state of the alarm entity (null = no entity / not known yet). */
    val state: StateFlow<String?> = _state.asStateFlow()

    private var entity: String? = null
    private var subscription: Job? = null

    init {
        scope.launch {
            _state.collectLatest { s ->
                if (!isActive(s)) return@collectLatest
                while (true) {
                    requestKeepAwake()
                    delay(KEEP_AWAKE_MS)
                }
            }
        }
    }

    fun setEntity(entityId: String?) {
        if (entityId == entity) return
        entity = entityId
        subscription?.cancel()
        _state.value = null
        if (entityId == null) return
        subscription = scope.launch {
            try {
                hass.subscribe(jsonOf("type" to "subscribe_entities", "entity_ids" to listOf(entityId))).collect { event ->
                    parseState(event.asObject(), entityId)?.let { _state.value = it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "alarm subscription for $entityId failed: ${e.message}")
            }
        }
    }

    /** Asks the service to wake the display and restart its standby timer (also used for popups). */
    fun requestKeepAwake() {
        try {
            context.sendBroadcast(Intent(AlarmContract.ACTION_KEEP_AWAKE).setPackage(context.packageName))
        } catch (e: Exception) {
            Log.w(TAG, "keep-awake request failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AlarmWatcher"
        private const val KEEP_AWAKE_MS = 15_000L

        fun isActive(state: String?) = state == "triggered" || state == "pending"

        /** State from a `subscribe_entities` event: `a` (full state) or `c` (diff with `+`). */
        internal fun parseState(event: kotlinx.serialization.json.JsonObject?, entityId: String): String? {
            event ?: return null
            event.obj("a")?.obj(entityId)?.string("s")?.let { return it }
            return event.obj("c")?.obj(entityId)?.obj("+")?.string("s")
        }
    }
}

/**
 * Full-screen pulsing glow while the alarm is active: red for `triggered`, orange for `pending`.
 * Drawn on top of the popups (the keypad popup is full-screen on small panels), with a light centre
 * so the keypad stays readable. Draws only, it has no pointer input, so touches go through.
 * [showLabel] false hides the pill (a popup is open: its title says it, and on small panels the
 * pill would cover the keypad's bottom row).
 */
@Composable
fun AlarmOverlayLayer(state: String?, showLabel: Boolean = true) {
    if (!AlarmWatcher.isActive(state)) return
    val triggered = state == "triggered"
    val color = if (triggered) Color(0xFFFF1A1A) else Color(0xFFFF8C00)
    val periodMs = if (triggered) 900 else 1800
    val pulse by rememberInfiniteTransition(label = "alarm-overlay").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "alarm-pulse",
    )
    val t = HaTheme.typography
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(color.copy(alpha = 0.04f + 0.14f * pulse))
                // Glowing edges: transparent centre, saturated border.
                drawRect(
                    Brush.radialGradient(
                        0.5f to Color.Transparent,
                        1f to color.copy(alpha = 0.55f + 0.4f * pulse),
                        center = center,
                        radius = size.maxDimension * 0.75f,
                    )
                )
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (showLabel) BasicText(
            if (triggered) "🚨 ALARM" else "🔒 Code eingeben",
            style = t.style(t.size2xl, FontWeight.Bold, color = Color.White),
            modifier = Modifier
                .padding(bottom = 20.dp)
                .background(color.copy(alpha = 0.85f), RoundedCornerShape(50))
                .padding(horizontal = 24.dp, vertical = 10.dp),
        )
    }
}
