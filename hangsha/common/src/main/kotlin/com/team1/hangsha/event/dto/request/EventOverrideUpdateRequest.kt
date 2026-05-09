package com.team1.hangsha.event.dto.request

data class EventOverrideUpdateRequest(
    val lockFields: List<String> = emptyList(),
    val unlockFields: List<String> = emptyList(),
)