package com.mabahrami.margin

import android.content.Context
import android.view.MotionEvent
import android.view.MotionPredictor
import androidx.annotation.RequiresApi

/**
 * Android's own pen motion predictor (Android 14+). It guesses where the pen will be in a moment, so a
 * short tail can be drawn ahead of the real line to hide display delay. The guess is only ever drawn,
 * never stored. Every call is guarded: if anything goes wrong, there's simply no prediction.
 */
@RequiresApi(34)
class PenPredictor(context: Context) {

    private val p = MotionPredictor(context)

    fun record(e: MotionEvent) {
        try {
            p.record(e)
        } catch (t: Throwable) {
        }
    }

    fun available(e: MotionEvent): Boolean = try {
        p.isPredictionAvailable(e.deviceId, e.source)
    } catch (t: Throwable) {
        false
    }

    /** Predicted pen event for now (positions in view pixels), or null. The caller must recycle it. */
    fun predict(): MotionEvent? = try {
        p.predict(System.nanoTime())
    } catch (t: Throwable) {
        null
    }
}
