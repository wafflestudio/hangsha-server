package com.team1.hangsha.user.controller

import com.team1.hangsha.user.dto.LoginRequest
import com.team1.hangsha.user.dto.LoginResponse
import com.team1.hangsha.user.dto.RegisterRequest
import com.team1.hangsha.user.dto.RegisterResponse
import com.team1.hangsha.user.dto.RefreshResponse
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import com.team1.hangsha.user.service.UserService
import com.team1.hangsha.user.AuthCookieSupport
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val userService: UserService,
    private val cookieSupport: AuthCookieSupport,
) {
    @PostMapping("/register")
    fun localRegister(@RequestBody req: RegisterRequest): ResponseEntity<RegisterResponse> {
        userService.localRegister(req.email, req.password, req.username)
        val issued = userService.issueAfterLocalLogin(req.email, req.password)

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, issued.refreshCookie.toString())
            .body(RegisterResponse(accessToken = issued.accessToken))
    }

    @PostMapping("/login")
    fun localLogin(@RequestBody req: LoginRequest): ResponseEntity<LoginResponse> {
        val issued = userService.issueAfterLocalLogin(req.email, req.password)

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, issued.refreshCookie.toString())
            .body(LoginResponse(accessToken = issued.accessToken))
    }

    @PostMapping("/refresh")
    fun refresh(
        @CookieValue(name = "refreshToken", required = false) refreshToken: String?
    ): ResponseEntity<RefreshResponse> {
        if (refreshToken.isNullOrBlank()) throw DomainException(ErrorCode.AUTH_UNAUTHORIZED)

        val issued = userService.rotateAndIssueAccessToken(refreshToken)

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, issued.refreshCookie.toString())
            .body(RefreshResponse(accessToken = issued.accessToken))
    }

    @PostMapping("/logout")
    fun logout(
        @CookieValue(name = "refreshToken", required = false) refreshToken: String?
    ): ResponseEntity<Unit> {
        userService.logout(refreshToken)

        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, cookieSupport.clearRefreshCookie().toString())
            .build()
    }
}