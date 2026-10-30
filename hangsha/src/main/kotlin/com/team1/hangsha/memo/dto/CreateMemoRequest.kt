package com.team1.hangsha.memo.dto

data class CreateMemoRequest(
    val eventId: Long,
    val content: String,
    val tagNames: List<String> = emptyList()
)