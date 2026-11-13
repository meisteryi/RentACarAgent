package com.hyundai.rentalhelper.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.hyundai.rentalhelper.service.CoachOverlayService

/**
 * 순정 내비게이션(Navi42) 실행 및 백그라운드 음성 코칭(CoachOverlayService) 기동 공용 헬퍼
 */
object NavigationLauncher {
    private const val TAG = "NavigationLauncher"
    private const val NATIVE_NAVI_PACKAGE = "ai.umos.maps.android.navigation.app"
    private const val NATIVE_NAVI_ACTIVITY = "ai.umos.maps.android.navigation.app.MainActivity"

    fun launchNativeNavigationWithCoach(activity: Activity) {
        val coachIntent = Intent(activity, CoachOverlayService::class.java).apply {
            action = CoachOverlayService.ACTION_START_COACHING
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(activity, coachIntent)
        } else {
            activity.startService(coachIntent)
        }

        try {
            val navIntent = Intent().apply {
                component = ComponentName(NATIVE_NAVI_PACKAGE, NATIVE_NAVI_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            activity.startActivity(navIntent)
            Log.i(TAG, "Navi42 startActivity 요청이 반환됨")
        } catch (e: Exception) {
            Log.w(TAG, "순정 내비 호출 실패, 시스템 맵 인텐트로 폴백 시도", e)
            try {
                val fallbackIntent = activity.packageManager.getLaunchIntentForPackage(NATIVE_NAVI_PACKAGE)
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:37.5665,126.9780"))
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(fallbackIntent)
            } catch (fallbackEx: Exception) {
                activity.stopService(Intent(activity, CoachOverlayService::class.java))
                activity.startActivity(Intent(activity, MainAssistantActivity::class.java))
            }
        }
    }
}
