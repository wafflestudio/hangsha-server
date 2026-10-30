package com.team1.hangsha.memo.dto

data class UpdateMemoRequest(
    val content: String? = null,
    val tagNames: List<String>? = null,
)