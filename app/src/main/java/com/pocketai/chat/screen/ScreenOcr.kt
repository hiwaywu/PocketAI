package com.pocketai.chat.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 2.20: when an app hides its text from accessibility services (the Reddit app read as 0 characters on the owner's
 * Fold), the button takes a screenshot of that app's window and reads the text in it on the phone with ML Kit text
 * recognition (Google Play services' model: no extra app size; the Chinese model also reads English). Nothing leaves
 * the phone except the recognised text, which then goes the usual way (e.g. Reddit title → whole thread).
 * Call from a background thread.
 */
object ScreenOcr {
    class Result(val pieces: List<String>, val log: String)

    fun read(service: AccessibilityService, windowId: Int?): Result {
        if (Build.VERSION.SDK_INT < 30) return Result(emptyList(), "ocr: needs Android 11+")
        val shot = screenshot(service, windowId)
        val bitmap = shot.first ?: return Result(emptyList(), "ocr: ${shot.second}")
        return try {
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 15, TimeUnit.SECONDS)
            recognizer.close()
            // One piece per block (a paragraph or a title), top to bottom.
            val pieces = text.textBlocks
                .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                .map { b -> b.lines.joinToString(" ") { it.text.trim() } }
                .filter { it.isNotBlank() }
            Result(pieces, "ocr: ${shot.second}, ${pieces.size} blocks, ${pieces.sumOf { it.length }} chars")
        } catch (e: Exception) {
            Result(emptyList(), "ocr: recognition failed (${e.javaClass.simpleName}: ${e.message?.take(120)})")
        } finally {
            bitmap.recycle()
        }
    }

    /** The app's window alone on Android 14+ (no Pocket AI bubble in it), else the whole screen. */
    private fun screenshot(service: AccessibilityService, windowId: Int?): Pair<Bitmap?, String> {
        if (Build.VERSION.SDK_INT < 30) return null to "too old"
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        var problem = "no screenshot"
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                try {
                    bitmap = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    r.hardwareBuffer.close()
                    latch.countDown()
                }
            }

            override fun onFailure(errorCode: Int) {
                problem = "screenshot refused (code $errorCode${if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) ", the app blocks screenshots" else ""})"
                latch.countDown()
            }
        }
        val whole = Build.VERSION.SDK_INT < 34 || windowId == null
        try {
            if (whole) service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, callback)
            else service.takeScreenshotOfWindow(windowId!!, service.mainExecutor, callback)
        } catch (e: Exception) {
            return null to "screenshot failed (${e.message?.take(80)})"
        }
        latch.await(5, TimeUnit.SECONDS)
        return bitmap to (if (bitmap != null) (if (whole) "screen" else "window") + " screenshot" else problem)
    }
}
