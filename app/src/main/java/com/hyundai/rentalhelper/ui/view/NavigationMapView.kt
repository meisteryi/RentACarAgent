package com.hyundai.rentalhelper.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * 현대자동차 순정 ccNC (connected car Navigation Cockpit) 스타일 정밀 도로 GIS 지도 뷰
 * 실제 현대 순정 내비게이션의 도로 체계, 하천, 건물 블록, 추천 경로선 및 차량 포인터를 재현합니다.
 */
class NavigationMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 지도 기본 배경 (ccNC 야간 다크 테마)
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#12151B")
        style = Paint.Style.FILL
    }

    // 도심 블록 / 단지 배경
    private val blockPaint = Paint().apply {
        color = Color.parseColor("#181D26")
        style = Paint.Style.FILL
    }

    // 하천 / 강 (한강 스타일 네이비 블루)
    private val riverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#142236")
        style = Paint.Style.FILL
    }

    // 일반 간선 도로
    private val localRoadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#262D3B")
        strokeWidth = 26f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // 고속화 도로 / 대로 (진한 그레이블루)
    private val mainRoadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#323B4E")
        strokeWidth = 48f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // 차선 중앙 점선
    private val laneDottedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4D5B73")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(18f, 18f), 0f)
    }

    // 현대 ccNC 순정 경로 안내선 (네온 아쿠아 블루 Glow)
    private val routeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4D00AAD2")
        strokeWidth = 36f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // 현대 ccNC 순정 경로 안내선 본체
    private val routeMainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00AAD2")
        strokeWidth = 22f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // 경로 방향 화살표 페인트
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // 차량 위치 포인터 (현대 순정 형광 청록 삼각 포인터)
    private val carPointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    private val carPointerGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3300E5FF")
        style = Paint.Style.FILL
    }

    private val carPointerBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
    }

    // 도로명 텍스트 페인트
    private val roadTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8E9CAE")
        textSize = 22f
        isFakeBoldText = true
    }

    private val landmarkTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5A697D")
        textSize = 20f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. 기본 배경 렌더링
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. 도심 블록/단지 영역 폴리곤
        canvas.drawRect(w * 0.05f, h * 0.08f, w * 0.40f, h * 0.45f, blockPaint)
        canvas.drawRect(w * 0.58f, h * 0.48f, w * 0.95f, h * 0.85f, blockPaint)
        canvas.drawRect(w * 0.58f, h * 0.08f, w * 0.95f, h * 0.38f, blockPaint)

        // 3. 하천/강 유역 (곡선 패스)
        val riverPath = Path().apply {
            moveTo(0f, h * 0.18f)
            cubicTo(w * 0.35f, h * 0.12f, w * 0.65f, h * 0.25f, w, h * 0.15f)
            lineTo(w, h * 0.0f)
            lineTo(0f, 0f)
            close()
        }
        canvas.drawPath(riverPath, riverPaint)

        // 4. 주요 간선 도로망 렌더링
        // 메인 대로 (가로 1)
        canvas.drawLine(0f, h * 0.46f, w, h * 0.46f, mainRoadPaint)
        canvas.drawLine(0f, h * 0.46f, w, h * 0.46f, laneDottedPaint)

        // 메인 대로 (가로 2 - 하단)
        canvas.drawLine(0f, h * 0.78f, w, h * 0.78f, mainRoadPaint)
        canvas.drawLine(0f, h * 0.78f, w, h * 0.78f, laneDottedPaint)

        // 세로 주간선 (중앙 좌측)
        canvas.drawLine(w * 0.48f, 0f, w * 0.48f, h, mainRoadPaint)
        canvas.drawLine(w * 0.48f, 0f, w * 0.48f, h, laneDottedPaint)

        // 보조 도로망
        canvas.drawLine(w * 0.20f, h * 0.20f, w * 0.20f, h, localRoadPaint)
        canvas.drawLine(w * 0.76f, 0f, w * 0.76f, h, localRoadPaint)
        canvas.drawLine(0f, h * 0.62f, w * 0.48f, h * 0.62f, localRoadPaint)

        // 5. 현대 ccNC 순정 스타일 안내 경로 (Route Path)
        val routePath = Path().apply {
            moveTo(w * 0.48f, h * 0.95f)
            lineTo(w * 0.48f, h * 0.52f)
            // 500m 앞 우회전 코너링 커브
            quadTo(w * 0.48f, h * 0.46f, w * 0.54f, h * 0.46f)
            lineTo(w * 1.0f, h * 0.46f)
        }
        canvas.drawPath(routePath, routeGlowPaint)
        canvas.drawPath(routePath, routeMainPaint)

        // 경로 위 방향 유도 꺾쇠 화살표 (Chevrons)
        drawChevron(canvas, w * 0.48f, h * 0.82f, 0f)
        drawChevron(canvas, w * 0.48f, h * 0.64f, 0f)
        drawChevron(canvas, w * 0.70f, h * 0.46f, 90f)
        drawChevron(canvas, w * 0.88f, h * 0.46f, 90f)

        // 6. 현재 차량 위치 마커 (초록/아쿠아 포인터 + 부채꼴 시야광)
        val carX = w * 0.48f
        val carY = h * 0.72f

        // 전방 조사각 부채꼴 빔
        val conePath = Path().apply {
            moveTo(carX, carY)
            lineTo(carX - 45f, carY - 90f)
            quadTo(carX, carY - 105f, carX + 45f, carY - 90f)
            close()
        }
        canvas.drawPath(conePath, carPointerGlow)

        // 차량 삼각 헤드 포인터
        val pointerPath = Path().apply {
            moveTo(carX, carY - 24f)
            lineTo(carX + 16f, carY + 18f)
            lineTo(carX, carY + 8f)
            lineTo(carX - 16f, carY + 18f)
            close()
        }
        canvas.drawPath(pointerPath, carPointerPaint)
        canvas.drawPath(pointerPath, carPointerBorder)

        // 7. 순정 도로명 & 랜드마크 라벨 렌더링
        canvas.drawText("양재대로", w * 0.56f, h * 0.43f, roadTextPaint)
        canvas.drawText("강남대로 (수서IC 방면)", w * 0.28f, h * 0.75f, roadTextPaint)
        canvas.drawText("헌릉로", w * 0.32f, h * 0.43f, roadTextPaint)
        canvas.drawText("현대자동차 양재 본사", w * 0.60f, h * 0.58f, landmarkTextPaint)
        canvas.drawText("시민의 숲", w * 0.12f, h * 0.35f, landmarkTextPaint)
    }

    private fun drawChevron(canvas: Canvas, x: Float, y: Float, angleDegrees: Float) {
        canvas.save()
        canvas.rotate(angleDegrees, x, y)
        val chevron = Path().apply {
            moveTo(x - 9f, y + 6f)
            lineTo(x, y - 6f)
            lineTo(x + 9f, y + 6f)
        }
        canvas.drawPath(chevron, chevronPaint)
        canvas.restore()
    }
}
