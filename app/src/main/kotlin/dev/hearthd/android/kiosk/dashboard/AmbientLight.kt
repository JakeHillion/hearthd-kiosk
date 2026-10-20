package dev.hearthd.android.kiosk.dashboard

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The current ambient-light reading in lux, or null when the device has no light
 * sensor (or hasn't reported a first value yet). Backed by the platform light
 * sensor for as long as the caller is composed: the listener is registered on
 * enter and released on dispose, so nothing runs when no screensaver needs it.
 *
 * This is the same sensor the "Sensors" settings pane reads; here it drives the
 * ambient screensaver's dark-room trigger.
 */
@Composable
fun rememberAmbientLightLux(): State<Float?> {
    val context = LocalContext.current
    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }
    val sensor = remember(sensorManager) {
        sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
    }
    val lux = remember { mutableStateOf<Float?>(null) }

    DisposableEffect(sensor) {
        val sm = sensorManager
        if (sm == null || sensor == null) return@DisposableEffect onDispose { }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                lux.value = event.values.firstOrNull()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sm.unregisterListener(listener) }
    }

    return lux
}
