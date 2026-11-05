package com.hyundai.rentalhelper

import android.app.Application
import android.util.Log
import com.hyundai.rentalhelper.data.CarButtonDatabase
import com.hyundai.rentalhelper.data.UserPreferences

class RentalCoachApplication : Application() {
    companion object {
        private const val TAG = "RentalCoachApplication"
    }

    lateinit var userPreferences: UserPreferences
        private set

    lateinit var buttonDatabase: CarButtonDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "현대차 렌터카 적응 코칭 비서 앱 구동 시작")
        userPreferences = UserPreferences(this)
        buttonDatabase = CarButtonDatabase.getInstance(this)
    }
}

