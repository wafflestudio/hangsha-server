package com.team1.hangsha.memo.dto

import com.team1.hangsha.memo.dto.core.MemoResponse
import com.team1.hangsha.memo.dto.core.MemoWithEventResponse

data class ListMemoResponse(
    val items: List<MemoResponse>
)

data class ListMemoWithEventResponse(
    val items: List<MemoWithEventResponse>
)