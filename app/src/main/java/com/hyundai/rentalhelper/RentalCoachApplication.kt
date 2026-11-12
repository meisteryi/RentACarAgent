package com.hyundai.rentalhelper

import android.app.Application
import android.util.Log
import com.hyundai.rentalhelper.data.CarButtonDatabase
import com.hyundai.rentalhelper.data.UserPreferences
import com.hyundai.rentalhelper.service.RendyWakeWordController
import com.hyundai.rentalhelper.service.VoiceAssistantEngine
import java.util.UUID

class RentalCoachApplication : Application() {
    companion object {
        private const val TAG = "RentalCoachApplication"
        private const val RENDY_SESSION_PREFS = "rendy_tts_session"
        private const val KEY_ACTIVE_SESSION = "active_session_id"
        private const val KEY_INTRO_ATTEMPTED_SESSION = "intro_attempted_session_id"
    }

    lateinit var userPreferences: UserPreferences
        private set

    lateinit var buttonDatabase: CarButtonDatabase
        private set

    lateinit var voiceAssistantEngine: VoiceAssistantEngine
        private set

    private val rendySessionPrefs by lazy {
        getSharedPreferences(RENDY_SESSION_PREFS, MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "현대차 렌터카 적응 코칭 비서 앱 구동 시작")
        userPreferences = UserPreferences(this)
        buttonDatabase = CarButtonDatabase.getInstance(this)
        voiceAssistantEngine = VoiceAssistantEngine(this, buttonDatabase)
        RendyWakeWordController.prepareProcessModel(this)
    }

    /** Called once when a new launcher session begins. Persists through service/process recreation. */
    fun beginRendyTtsSession() {
        val sessionId = UUID.randomUUID().toString()
        rendySessionPrefs.edit().putString(KEY_ACTIVE_SESSION, sessionId).commit()
        Log.i(TAG, "RENDY TTS session started: $sessionId")
    }

    /** Claims the automatic greeting once per launcher session, including after Service recreation. */
    @Synchronized
    fun claimRendyIntroForCurrentSession(): Boolean {
        val sessionId = rendySessionPrefs.getString(KEY_ACTIVE_SESSION, null)
            ?: UUID.randomUUID().toString().also {
                rendySessionPrefs.edit().putString(KEY_ACTIVE_SESSION, it).commit()
            }
        if (rendySessionPrefs.getString(KEY_INTRO_ATTEMPTED_SESSION, null) == sessionId) {
            Log.i(TAG, "RENDY automatic intro already attempted for session=$sessionId")
            return false
        }
        rendySessionPrefs.edit().putString(KEY_INTRO_ATTEMPTED_SESSION, sessionId).commit()
        Log.i(TAG, "RENDY automatic intro claimed for session=$sessionId")
        return true
    }
}
