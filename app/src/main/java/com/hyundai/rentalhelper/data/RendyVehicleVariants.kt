package com.hyundai.rentalhelper.data

/**
 * Design variants of a DB model. The DB keeps one `IONIQ5` entry (routing, design-neutral data); a variant adds
 * the design identity so design-specific verified data (overlay `design_generation`) is chosen for it and the
 * other design's data is never mixed in.
 *
 * Source: RENDY_IONIQ5_SCC_button_handoff.md (2026-11-13), official Korean web manuals (ownersmanual.hyundai.com):
 * 2025·2026 manuals = 더 뉴 아이오닉 5 design, 2023·2024 manuals = pre-facelift design. Which registration year
 * maps to which design is NOT verified, so a variant is chosen by design, never derived from a model year.
 */
object RendyVehicleVariants {
    const val FACELIFT_THE_NEW = "facelift_the_new"
    const val PRE_FACELIFT = "pre_facelift"

    data class Variant(
        val variantId: String,
        val baseModelId: String,
        val modelCode: String,
        val designGeneration: String,
        val modelNameKo: String,
        val modelNameEn: String,
        /** Official manual editions this design's data was checked against. */
        val manualYearsVerified: List<Int>,
        val sourceUrls: List<String>
    )

    val IONIQ5_FACELIFT = Variant(
        variantId = "IONIQ5_NE1_FL",
        baseModelId = "IONIQ5",
        modelCode = "NE1",
        designGeneration = FACELIFT_THE_NEW,
        modelNameKo = "더 뉴 아이오닉 5",
        modelNameEn = "The new IONIQ 5",
        manualYearsVerified = listOf(2025, 2026),
        sourceUrls = listOf(
            "https://ownersmanual.hyundai.com/full_webhelp/NE1/2025/ko_KR/id23C3IH040YK.html",
            "https://ownersmanual.hyundai.com/full_webhelp/NE1/2025/ko_KR/id222PF0ZL0HS.html",
            "https://ownersmanual.hyundai.com/full_webhelp/NE1/2026/ko_KR/id222PF0ZL0HS.html"
        )
    )

    val IONIQ5_PRE_FACELIFT = Variant(
        variantId = "IONIQ5_NE1_PRE",
        baseModelId = "IONIQ5",
        modelCode = "NE1",
        designGeneration = PRE_FACELIFT,
        modelNameKo = "구형 아이오닉 5",
        modelNameEn = "IONIQ 5 (pre-facelift)",
        manualYearsVerified = listOf(2023, 2024),
        sourceUrls = listOf(
            "https://ownersmanual.hyundai.com/full_webhelp/NE1/2024/ko_KR/picture_index_interior_overview.html",
            "https://ownersmanual.hyundai.com/full_webhelp/NE1/2024/ko_KR/id3388eb588e7.html",
            "https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id3388eb588e7.html",
            "https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html"
        )
    )

    val all: List<Variant> = listOf(IONIQ5_FACELIFT, IONIQ5_PRE_FACELIFT)

    /** The demo default car: 더 뉴 아이오닉 5. */
    val DEFAULT: Variant = IONIQ5_FACELIFT

    /** Design generations known for a DB model (overlay entries naming any other value are rejected). */
    fun generationsFor(baseModelId: String?): Set<String> =
        all.filter { it.baseModelId == baseModelId }.mapTo(mutableSetOf()) { it.designGeneration }

    fun find(variantId: String): Variant? = all.firstOrNull { it.variantId.equals(variantId, ignoreCase = true) }

    /** The base DB model carrying [variantId]'s identity, or null when [variantId] is not a variant id. */
    fun resolve(models: List<HyundaiCarModel>, variantId: String): HyundaiCarModel? {
        val v = find(variantId) ?: return null
        val base = models.firstOrNull { it.modelId == v.baseModelId } ?: return null
        return apply(base, v)
    }

    fun apply(base: HyundaiCarModel, v: Variant): HyundaiCarModel {
        require(base.modelId == v.baseModelId) { "${v.variantId} is a variant of ${v.baseModelId}, not ${base.modelId}" }
        return base.copy(
            modelNameKo = v.modelNameKo,
            modelNameEn = v.modelNameEn,
            // The DB's "2021~2024" range is not a design statement; the variant states what it was checked against.
            modelYear = "${v.manualYearsVerified.joinToString("·")} 공식 매뉴얼 기준",
            modelCode = v.modelCode,
            designGeneration = v.designGeneration,
            variantId = v.variantId
        )
    }
}
