package com.team1.hangsha.user.dto

import com.fasterxml.jackson.databind.JsonNode

data class UpdateTutorialStateRequest(
    val tutorialState: JsonNode,
)
