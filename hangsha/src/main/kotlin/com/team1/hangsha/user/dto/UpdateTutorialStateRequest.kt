package com.team1.hangsha.user.dto

data class UpdateTutorialStateRequest(
    val tutorialState: Map<String, Boolean> = emptyMap(),
)
