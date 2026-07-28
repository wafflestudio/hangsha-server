package com.team1.hangsha.admin

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/auth")
class AdminAuthController(
    private val adminAuthService: AdminAuthService,
) {
    @PostMapping("/session")
    fun createSession(@RequestBody req: AdminSessionRequest): AdminSessionResponse {
        return AdminSessionResponse(accessToken = adminAuthService.issueAccessToken(req.code))
    }
}

data class AdminSessionRequest(
    val code: String,
)

data class AdminSessionResponse(
    val accessToken: String,
)
