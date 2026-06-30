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
import com.team1.hangsha.user.LoggedInUser
import com.team1.hangsha.user.dto.AndroidLogoutRequest
import com.team1.hangsha.user.model.User
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import com.team1.hangsha.user.dto.AndroidRefreshRequest
import com.team1.hangsha.user.dto.AndroidTokenResponse

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

    @PostMapping("/session")
    fun establishSession(
        @Parameter(hidden = true)
        @LoggedInUser user: User?
    ): ResponseEntity<Unit> {
        val authenticatedUser = user ?: throw DomainException(ErrorCode.AUTH_UNAUTHORIZED)
        val issued = userService.issueAfterSocialLogin(authenticatedUser.id!!)

        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, issued.refreshCookie.toString())
            .build()
    }

    @PostMapping("/logout")
    @Operation(
        summary = "로그아웃",
        description = "리프레시 토큰을 무효화합니다.\n응답으로 리프레시 토큰 쿠키를 만료 처리합니다."
    )
    fun logout(
        @Parameter(hidden = true)
        @CookieValue(name = "refreshToken", required = false) refreshToken: String?
    ): ResponseEntity<Unit> {
        userService.logout(refreshToken)

        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, cookieSupport.clearRefreshCookie().toString())
            .build()
    }

    @PostMapping("/android/login")
    fun androidLocalLogin(@RequestBody req: LoginRequest): ResponseEntity<AndroidTokenResponse> {
        val issued = userService.issueAfterLocalLogin(req.email, req.password)

        return ResponseEntity.ok(
            AndroidTokenResponse(
                accessToken = issued.accessToken,
                refreshToken = issued.refreshToken
            )
        )
    }

    @PostMapping("/android/refresh")
    fun androidRefresh(@RequestBody req: AndroidRefreshRequest): ResponseEntity<AndroidTokenResponse> {
        if (req.refreshToken.isBlank()) throw DomainException(ErrorCode.AUTH_UNAUTHORIZED)

        val issued = userService.rotateAndIssueAccessToken(req.refreshToken)

        return ResponseEntity.ok(
            AndroidTokenResponse(
                accessToken = issued.accessToken,
                refreshToken = issued.refreshToken
            )
        )
    }

    @PostMapping("/android/logout")
    @Operation(
        summary = "안드로이드 로그아웃",
        description = "안드로이드 클라이언트의 리프레시 토큰을 JSON Body로 받아 무효화합니다."
    )
    fun androidLogout(
        @RequestBody req: AndroidLogoutRequest
    ): ResponseEntity<Unit> {
        userService.logout(req.refreshToken)

        return ResponseEntity.noContent().build()
    }
}
