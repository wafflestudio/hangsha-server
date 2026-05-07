package com.team1.hangsha.bugreport.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateBugReportRequest(
    @field:NotBlank
    @field:Size(max = 200)
    val title: String,

    @field:NotBlank
    @field:Size(max = 5000)
    val content: String,
)
