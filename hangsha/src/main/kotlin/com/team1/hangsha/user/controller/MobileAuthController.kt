package com.team1.hangsha.user.controller

import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import com.team1.hangsha.user.dto.LoginRequest
import com.team1.hangsha.user.dto.MobileLogoutRequest
import com.team1.hangsha.user.dto.MobileRefreshRequest
import com.team1.hangsha.user.dto.MobileTokenResponse
import com.team1.hangsha.user.dto.RegisterRequest
import com.team1.hangsha.user.service.UserService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/mobile/auth")
class MobileAuthController(
    private val userService: UserService,
) {
    @PostMapping("/register")
    fun mobileLocalRegister(@RequestBody req: RegisterRequest): ResponseEntity<MobileTokenResponse> {
        userService.localRegister(req.email, req.password, req.username)
        val issued = userService.issueAfterLocalLogin(req.email, req.password)

        return ResponseEntity.ok(
            MobileTokenResponse(
                accessToken = issued.accessToken,
                refreshToken = issued.refreshToken
            )
        )
    }

    @PostMapping("/login")
    fun mobileLocalLogin(@RequestBody req: LoginRequest): ResponseEntity<MobileTokenResponse> {
        val issued = userService.issueAfterLocalLogin(req.email, req.password)

        return ResponseEntity.ok(
            MobileTokenResponse(
                accessToken = issued.accessToken,
                refreshToken = issued.refreshToken
            )
        )
    }

    @PostMapping("/refresh")
    fun mobileRefresh(@RequestBody req: MobileRefreshRequest): ResponseEntity<MobileTokenResponse> {
        if (req.refreshToken.isBlank()) throw DomainException(ErrorCode.AUTH_UNAUTHORIZED)

        val issued = userService.rotateAndIssueAccessToken(req.refreshToken)

        return ResponseEntity.ok(
            MobileTokenResponse(
                accessToken = issued.accessToken,
                refreshToken = issued.refreshToken
            )
        )
    }

    @PostMapping("/logout")
    fun mobileLogout(@RequestBody req: MobileLogoutRequest): ResponseEntity<Unit> {
        userService.logout(req.refreshToken)

        return ResponseEntity.noContent().build()
    }
}
