package com.team1.hangsha.memo.dto.core

import java.time.Instant
import java.time.LocalDateTime

data class MemoTagResponse(
    val id: Long,
    val name: String
)

/** 메모가 달린 행사의 주최기관 (events.org_id -> categories) */
data class MemoOrganizationResponse(
    val id: Long,
    val name: String
)

data class MemoResponse(
    val id: Long,
    val eventId: Long,
    val eventTitle: String,
    val content: String,
    val tags: List<MemoTagResponse>,
    val createdAt: Instant?,
    val updatedAt: Instant?
)

/** 메모 목록 조회용. 메모에 달린 행사 정보를 함께 내려준다. */
data class MemoWithEventResponse(
    val id: Long,
    val eventId: Long,
    val eventTitle: String,
    /** 메모가 달린 행사의 유형 카테고리 ID (events.event_type_id). 미분류이면 null */
    val eventTypeId: Long?,
    val content: String,
    val tags: List<MemoTagResponse>,
    val createdAt: Instant?,
    val updatedAt: Instant?,

    /** D-Day 계산 기준. 행사가 없거나 마감일 미정이면 null */
    val applyEnd: LocalDateTime?,
    /** 주최기관 미분류이면 null */
    val organization: MemoOrganizationResponse?,
    val isBookmarked: Boolean
)
