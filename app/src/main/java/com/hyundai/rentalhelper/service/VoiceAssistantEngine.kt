package com.hyundai.rentalhelper.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.hyundai.rentalhelper.data.CarButtonDatabase
import com.hyundai.rentalhelper.data.HyundaiCarModel
import java.util.Locale

/**
 * 음성 입력(STT) 및 차량 맞춤형 답변 음성 출력(TTS) 전담 엔진
 */
class VoiceAssistantEngine(
    private val context: Context,
    private val buttonDb: CarButtonDatabase
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "VoiceAssistantEngine"
        private const val UTTERANCE_ID_BRIEFING = "utterance_briefing"
        private const val UTTERANCE_ID_QA = "utterance_qa"
    }

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var speechRecognizer: SpeechRecognizer? = null

    // 상태 리스너
    var onListeningStateChanged: ((isListening: Boolean) -> Unit)? = null
    var onSpeechResultReceived: ((query: String, title: String, answer: String) -> Unit)? = null
    var onErrorOccurred: ((errorMessage: String) -> Unit)? = null

    private var pendingSpeechText: String? = null

    init {
        initTtsEngine()
        initSpeechRecognizer()
    }

    private fun initTtsEngine() {
        try {
            val tempTts = TextToSpeech(context.applicationContext, null)
            val availableEngines = tempTts.engines
            tempTts.shutdown()

            val selectedEngine = availableEngines.firstOrNull {
                it.name.contains("pleos", ignoreCase = true) ||
                it.name.contains("caas", ignoreCase = true) ||
                it.name.contains("google", ignoreCase = true)
            }?.name ?: availableEngines.firstOrNull()?.name

            tts = if (selectedEngine != null) {
                Log.i(TAG, "발견된 TTS 엔진 사용: $selectedEngine")
                TextToSpeech(context.applicationContext, this, selectedEngine)
            } else {
                TextToSpeech(context.applicationContext, this)
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS 엔진 탐색 실패, 기본 생성자 사용", e)
            tts = TextToSpeech(context.applicationContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.KOREAN)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "한국어 TTS 음성 데이터 확인 필요 (기본 로케일 적용)")
                tts?.language = Locale.getDefault()
            }
            tts?.setSpeechRate(1.05f)
            tts?.setPitch(1.0f)
            isTtsReady = true
            Log.i(TAG, "TTS 엔진 초기화 성공")

            // 초기화 대기 중이던 텍스트가 있으면 즉시 발화
            pendingSpeechText?.let { text ->
                speak(text)
                pendingSpeechText = null
            }
        } else {
            Log.e(TAG, "TTS 엔진 초기화 실패: $status (오디오 출력 대체 모드)")
        }
    }

    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "STT 준비 완료: 듣기 시작")
                        onListeningStateChanged?.invoke(true)
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d(TAG, "STT 발화 종료")
                        onListeningStateChanged?.invoke(false)
                    }

                    override fun onError(error: Int) {
                        Log.w(TAG, "STT 에러 발생: $error")
                        onListeningStateChanged?.invoke(false)
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "말씀을 인식하지 못했습니다. 다시 말씀해 주세요."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "입력 시간이 초과되었습니다."
                            SpeechRecognizer.ERROR_AUDIO -> "오디오 입력 장치 오류입니다."
                            else -> "음성 인식 중 문제가 발생했습니다."
                        }
                        onErrorOccurred?.invoke(msg)
                    }

                    override fun onResults(results: Bundle?) {
                        onListeningStateChanged?.invoke(false)
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spokenText = matches?.firstOrNull() ?: return
                        Log.i(TAG, "STT 인식 성공: \"$spokenText\"")
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } else {
            Log.w(TAG, "이 기기(에뮬레이터)는 SpeechRecognizer를 지원하지 않습니다.")
        }
    }

    /**
     * 마이크 STT 음성 수신 시작
     */
    fun startListening(currentCarModel: HyundaiCarModel) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onErrorOccurred?.invoke("마이크 입력 미지원 환경입니다. 빠른 질문 버튼을 이용하세요.")
            return
        }

        // 재생 중인 TTS 중단
        stopSpeaking()

        speechRecognizer?.let { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    onListeningStateChanged?.invoke(true)
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    onListeningStateChanged?.invoke(false)
                }

                override fun onError(error: Int) {
                    onListeningStateChanged?.invoke(false)
                    val msg = if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                        "인식된 음성이 없습니다. 버튼을 누르고 다시 말씀하세요."
                    } else {
                        "음성 인식 오류 ($error)"
                    }
                    onErrorOccurred?.invoke(msg)
                }

                override fun onResults(results: Bundle?) {
                    onListeningStateChanged?.invoke(false)
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val query = matches?.firstOrNull()
                    if (!query.isNullOrBlank()) {
                        processQuestion(currentCarModel, query)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN.toString())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            try {
                recognizer.startListening(intent)
            } catch (e: Exception) {
                Log.e(TAG, "STT startListening 실패", e)
            }
        }
    }

    /**
     * 질문 텍스트 분석 및 답변 TTS 재생
     * (STT 결과 또는 화면 빠른 질문 버튼/adb 입력 공통 진입점)
     */
    fun processQuestion(carModel: HyundaiCarModel, query: String) {
        val (title, answer) = buttonDb.matchVoiceQuery(carModel, query)
        onSpeechResultReceived?.invoke(query, title, answer)
        speak(answer, UTTERANCE_ID_QA)
    }

    /**
     * TTS 음성 안내 송출 (1~2문장 극단적 간결성 준수)
     */
    fun speak(text: String, utteranceId: String = UTTERANCE_ID_BRIEFING, onCompleted: (() -> Unit)? = null) {
        if (!isTtsReady || tts == null) {
            Log.w(TAG, "TTS 초기화 대기 중: 발화 예약 등록 ($text)")
            pendingSpeechText = text
            return
        }

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                Log.d(TAG, "TTS 송출 시작 ($id)")
            }

            override fun onDone(id: String?) {
                Log.d(TAG, "TTS 송출 완료 ($id)")
                onCompleted?.invoke()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                Log.e(TAG, "TTS 송출 에러 ($id)")
            }
        })

        // 이전 음성 중단 후 즉시 재생 (QUEUE_FLUSH)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun destroy() {
        try {
            speechRecognizer?.destroy()
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "엔진 정리 예외", e)
        }
    }
}

