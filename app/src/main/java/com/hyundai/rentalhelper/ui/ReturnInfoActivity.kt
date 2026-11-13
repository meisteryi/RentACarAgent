package com.hyundai.rentalhelper.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.hyundai.rentalhelper.RentalCoachApplication
import com.hyundai.rentalhelper.data.UserPreferences
import com.hyundai.rentalhelper.databinding.ActivityReturnInfoBinding
import com.hyundai.rentalhelper.service.CoachOverlayService
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * [차종 선택 이후 단계: 반납지 및 반납 일시 입력 화면]
 * - 향후 반납 시간 임박 알림 및 목적지 안내에 사용할 반납지·일시 데이터를 수집/저장
 * - 입력 완료(또는 건너뛰기) 시 순정 내비게이션(Navi42) 및 차량 적응 음성 브리핑을 시작
 */
class ReturnInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReturnInfoBinding
    private lateinit var userPreferences: UserPreferences

    private val returnCalendar: Calendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("yyyy년 M월 d일 (E)", Locale.KOREAN)
    private val timeFormat = SimpleDateFormat("a hh:mm", Locale.KOREAN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        stopService(Intent(this, CoachOverlayService::class.java))

        userPreferences = (application as RentalCoachApplication).userPreferences

        binding = ActivityReturnInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initDefaultValues()
        setupListeners()
        updateDateTimeViews()
        updateConfirmButtonState()
    }

    override fun onStart() {
        super.onStart()
        stopService(Intent(this, CoachOverlayService::class.java))
    }

    private fun initDefaultValues() {
        val savedTime = userPreferences.getReturnTimeMillis()
        if (savedTime != null && savedTime > System.currentTimeMillis()) {
            returnCalendar.timeInMillis = savedTime
        } else {
            // 기본 반납 예정 시각: 오늘 오후 6시 이전이면 오늘 18:00, 이후면 내일 18:00
            if (returnCalendar.get(Calendar.HOUR_OF_DAY) >= 14) {
                returnCalendar.add(Calendar.DAY_OF_YEAR, 1)
            }
            returnCalendar.set(Calendar.HOUR_OF_DAY, 18)
            returnCalendar.set(Calendar.MINUTE, 0)
            returnCalendar.set(Calendar.SECOND, 0)
            returnCalendar.set(Calendar.MILLISECOND, 0)
        }

        userPreferences.getReturnLocation()?.let { savedLocation ->
            binding.etReturnLocation.setText(savedLocation)
            binding.etReturnLocation.setSelection(savedLocation.length)
        }
    }

    private fun setupListeners() {
        binding.etReturnLocation.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updateConfirmButtonState()
            }
        })

        binding.etReturnLocation.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
                true
            } else {
                false
            }
        }

        // 자주 찾는 반납지 퀵 칩
        val locationChips = listOf(
            binding.chipLocGimpo to "김포공항 렌터카 하우스",
            binding.chipLocIncheon to "인천공항 렌터카 하우스",
            binding.chipLocSeoulStation to "서울역 공영주차장",
            binding.chipLocJeju to "제주공항 렌터카 하우스"
        )
        locationChips.forEach { (chip, value) ->
            chip.setOnClickListener {
                binding.etReturnLocation.setText(value)
                binding.etReturnLocation.setSelection(value.length)
            }
        }

        // 날짜/시간 다이얼로그
        binding.btnSelectDate.setOnClickListener { showDatePicker() }
        binding.btnSelectTime.setOnClickListener { showTimePicker() }

        // 반납 일시 퀵 칩
        binding.chipTimePlus2h.setOnClickListener {
            returnCalendar.timeInMillis = System.currentTimeMillis()
            returnCalendar.add(Calendar.HOUR_OF_DAY, 2)
            returnCalendar.set(Calendar.SECOND, 0)
            returnCalendar.set(Calendar.MILLISECOND, 0)
            updateDateTimeViews()
        }
        binding.chipTimeToday18.setOnClickListener { applyQuickDayHour(daysOffset = 0, hour = 18) }
        binding.chipTimeTomorrow10.setOnClickListener { applyQuickDayHour(daysOffset = 1, hour = 10) }
        binding.chipTimeTomorrow18.setOnClickListener { applyQuickDayHour(daysOffset = 1, hour = 18) }

        binding.btnBack.setOnClickListener {
            startActivity(Intent(this, StartupCarSelectActivity::class.java))
            finish()
        }

        binding.btnSkip.setOnClickListener {
            proceedToNavigationAndBriefing()
        }

        binding.btnConfirm.setOnClickListener {
            val location = binding.etReturnLocation.text?.toString()?.trim().orEmpty()
            if (location.isBlank()) return@setOnClickListener

            userPreferences.saveReturnInfo(location, returnCalendar.timeInMillis)
            proceedToNavigationAndBriefing()
        }
    }

    private fun applyQuickDayHour(daysOffset: Int, hour: Int) {
        returnCalendar.timeInMillis = System.currentTimeMillis()
        if (daysOffset > 0) {
            returnCalendar.add(Calendar.DAY_OF_YEAR, daysOffset)
        }
        returnCalendar.set(Calendar.HOUR_OF_DAY, hour)
        returnCalendar.set(Calendar.MINUTE, 0)
        returnCalendar.set(Calendar.SECOND, 0)
        returnCalendar.set(Calendar.MILLISECOND, 0)
        updateDateTimeViews()
    }

    private fun showDatePicker() {
        val dialog = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                returnCalendar.set(Calendar.YEAR, year)
                returnCalendar.set(Calendar.MONTH, month)
                returnCalendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                updateDateTimeViews()
            },
            returnCalendar.get(Calendar.YEAR),
            returnCalendar.get(Calendar.MONTH),
            returnCalendar.get(Calendar.DAY_OF_MONTH)
        )
        dialog.datePicker.minDate = System.currentTimeMillis() - 1000L
        dialog.show()
    }

    private fun showTimePicker() {
        TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                returnCalendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                returnCalendar.set(Calendar.MINUTE, minute)
                returnCalendar.set(Calendar.SECOND, 0)
                returnCalendar.set(Calendar.MILLISECOND, 0)
                updateDateTimeViews()
            },
            returnCalendar.get(Calendar.HOUR_OF_DAY),
            returnCalendar.get(Calendar.MINUTE),
            false
        ).show()
    }

    private fun updateDateTimeViews() {
        binding.tvSelectedDate.text = dateFormat.format(returnCalendar.time)
        binding.tvSelectedTime.text = timeFormat.format(returnCalendar.time)
    }

    private fun updateConfirmButtonState() {
        val hasLocation = !binding.etReturnLocation.text.isNullOrBlank()
        binding.btnConfirm.isEnabled = hasLocation
        binding.btnConfirm.alpha = if (hasLocation) 1.0f else 0.45f
    }

    private fun proceedToNavigationAndBriefing() {
        NavigationLauncher.launchNativeNavigationWithCoach(this)
        setResult(RESULT_OK)
        finish()
    }
}

