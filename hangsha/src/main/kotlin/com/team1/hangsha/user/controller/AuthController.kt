package com.team1.hangsha.user.controller

import com.team1.hangsha.user.dto.LoginRequest
import com.team1.hangsha.user.dto.LoginResponse
import com.team1.hangsha.user.dto.RegisterRequest
import com.team1.hangsha.user.dto.RegisterResponse
import com.team1.hangsha.user.dto.RefreshResponse
import com.team1.hangsha.user.dto.SendEmailCodeRequest
import com.team1.hangsha.user.dto.SendEmailCodeResponse
import com.team1.hangsha.user.dto.VerifyEmailCodeRequest
import com.team1.hangsha.user.dto.VerifyEmailCodeResponse
import com.team1.hangsha.user.verification.EmailVerificationService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import com.team1.hangsha.user.service.UserService
import com.team1.hangsha.user.AuthCookieSupport
import com.team1.hangsha.user.LoggedInUser
import com.team1.hangsha.user.model.User
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val userService: UserService,
    private val cookieSupport: AuthCookieSupport,
    private val emailVerificationService: EmailVerificationService,
) {
    @PostMapping("/email/send-code")
    @Operation(
        summary = "회원가입 이메일 인증번호 발송",
        description = "입력한 이메일로 인증번호를 보냅니다.\n" +
            "'인증번호 다시 받기'도 같은 API를 다시 호출하면 됩니다(재발송 쿨다운 적용).",
    )
    fun sendEmailCode(@Valid @RequestBody req: SendEmailCodeRequest): SendEmailCodeResponse =
        SendEmailCodeResponse(expiresAt = emailVerificationService.sendCode(req.email))

    @PostMapping("/email/verify-code")
    @Operation(
        summary = "회원가입 이메일 인증번호 확인",
        description = "인증에 성공하면 signupToken을 발급합니다. 계정 생성 요청에 그대로 넘겨야 합니다.",
    )
    fun verifyEmailCode(@Valid @RequestBody req: VerifyEmailCodeRequest): VerifyEmailCodeResponse {
        val verified = emailVerificationService.verifyCode(req.email, req.code)

        return VerifyEmailCodeResponse(
            signupToken = verified.signupToken,
            expiresAt = verified.expiresAt,
        )
    }

    @PostMapping("/register")
    @Operation(
        summary = "계정 생성",
        description = "이메일 인증을 통과한 signupToken이 있어야 합니다.",
    )
    fun localRegister(@RequestBody req: RegisterRequest): ResponseEntity<RegisterResponse> {
        emailVerificationService.requireVerified(req.email, req.signupToken)

        userService.localRegister(req.email, req.password, req.username)
        // 계정 생성까지 끝난 뒤에 폐기한다. 비밀번호 정책에 걸렸을 때 재인증을 요구하지 않기 위해서다.
        emailVerificationService.consumeSignupToken(req.signupToken)

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
}
