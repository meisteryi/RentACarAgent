package com.hyundai.rentalhelper.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import com.hyundai.rentalhelper.service.CoachOverlayService

/** Small transparent relay so PTT can use Android's normal RECORD_AUDIO prompt above Navi42. */
class MicrophonePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startPleosRecognition(CoachOverlayService.ACTION_TOGGLE_PLEOS_STT)
        } else {
            Log.i("RendySTT", "Requesting RECORD_AUDIO through Android runtime permission dialog")
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MICROPHONE) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Log.i("RendySTT", "RECORD_AUDIO granted by user")
            startPleosRecognition(CoachOverlayService.ACTION_START_PLEOS_STT)
        } else {
            Log.w("RendySTT", "RECORD_AUDIO denied by user; microphone remains off")
            finish()
        }
    }

    private fun startPleosRecognition(action: String) {
        try {
            startService(Intent(this, CoachOverlayService::class.java).setAction(action))
        } catch (e: Exception) {
            Log.e("RendySTT", "Could not start PTT service action", e)
        }
        finish()
    }

    companion object { private const val REQUEST_MICROPHONE = 4103 }
}
