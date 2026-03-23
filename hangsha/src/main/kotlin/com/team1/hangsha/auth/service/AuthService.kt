package com.team1.hangsha.auth.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.team1.hangsha.auth.dto.SocialLoginRequest
import com.team1.hangsha.auth.dto.SocialLoginResult
import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import com.team1.hangsha.user.model.User
import com.team1.hangsha.user.repository.UserRepository
import com.team1.hangsha.user.service.UserService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val userService: UserService,

    @Value("\${spring.security.oauth2.client.registration.google.client-id}") val googleClientId: String,
    @Value("\${spring.security.oauth2.client.registration.google.client-secret}") val googleClientSecret: String,
    @Value("\${spring.security.oauth2.client.registration.google.redirect-uri}") val googleRedirectUri: String,

    // [수정됨] 카카오 시크릿 추가
    @Value("\${spring.security.oauth2.client.registration.kakao.client-id}") val kakaoClientId: String,
    @Value("\${spring.security.oauth2.client.registration.kakao.client-secret}") val kakaoClientSecret: String,
    @Value("\${spring.security.oauth2.client.registration.kakao.redirect-uri}") val kakaoRedirectUri: String,

    @Value("\${spring.security.oauth2.client.registration.naver.client-id}") val naverClientId: String,
    @Value("\${spring.security.oauth2.client.registration.naver.client-secret}") val naverClientSecret: String,
    // 네이버는 state 값이 필수인 경우가 많으나, 단순 토큰 교환 시 임의값 사용 가능
    @Value("\${spring.security.oauth2.client.registration.naver.redirect-uri}") val naverRedirectUri: String
) {
    private val restTemplate = RestTemplate()

    @Transactional
    fun socialLogin(req: SocialLoginRequest): SocialLoginResult {
        // 1. 소셜 프로필 가져오기
        val socialProfile = when (req.provider.uppercase()) {
            "GOOGLE" -> getGoogleProfile(req.code, req.codeVerifier) // codeVerifier 추가된 버전 유지
            "KAKAO" -> getKakaoProfile(req.code)
            "NAVER" -> getNaverProfile(req.code)
            else -> throw IllegalArgumentException("지원하지 않는 Provider입니다.")
        }

        // 2. 로그인 또는 회원가입 처리
        var isNewUser = false

        // 먼저 DB에서 찾아봅니다.
        var user = userRepository.findByEmail(socialProfile.email)

        // 없으면 회원가입 진행
        if (user == null) {
            user = userRepository.save(
                User(
                    email = socialProfile.email,
                    username = socialProfile.nickname,
                    profileImageUrl = socialProfile.profileImage
                )
            )
            isNewUser = true // 새로 만들었으니 true로 설정
        }

        // 3. 우리 서비스 전용 토큰 발급
        val issued = userService.issueAfterSocialLogin(user!!.id!!)

        return SocialLoginResult(
            accessToken = issued.accessToken,
            refreshCookie = issued.refreshCookie,
            isNewUser = isNewUser
        )
    }

    // --- Google ---
    private fun getGoogleProfile(code: String, codeVerifier: String?): SocialUserProfile {
        val tokenUrl = "https://oauth2.googleapis.com/token"

        val params = LinkedMultiValueMap<String, String>().apply {
            add("code", code)
            add("client_id", googleClientId)
            add("client_secret", googleClientSecret)
            add("redirect_uri", googleRedirectUri)
            add("grant_type", "authorization_code")
            if (codeVerifier != null) {
                add("code_verifier", codeVerifier) // PKCE 핵심
            }
        }

        val tokenResp = restTemplate.postForObject(tokenUrl, params, GoogleTokenResponse::class.java)
            ?: throw DomainException(ErrorCode.INTERNAL_ERROR)

        // ID Token 디코딩 대신 UserInfo API 호출 (간편함)
        val userInfoUrl = "https://www.googleapis.com/oauth2/v3/userinfo"
        val headers = HttpHeaders().apply { setBearerAuth(tokenResp.accessToken) }
        val entity = HttpEntity<Unit>(headers)

        val resp = restTemplate.exchange(userInfoUrl, HttpMethod.GET, entity, GoogleUserInfo::class.java)
        val userInfo = resp.body ?: throw DomainException(ErrorCode.INTERNAL_ERROR)

        return SocialUserProfile(userInfo.email, userInfo.name, userInfo.picture)
    }

    // --- Kakao ---
    private fun getKakaoProfile(code: String): SocialUserProfile {
        println(">>> DEBUG: ID=$kakaoClientId, SECRET=$kakaoClientSecret")
        val tokenUrl = "https://kauth.kakao.com/oauth/token"

        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }
        val params = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("client_id", kakaoClientId)
            add("redirect_uri", kakaoRedirectUri)
            add("code", code)
            // [수정됨] 시크릿 키 파라미터 추가 (KOE010 에러 해결)
            add("client_secret", kakaoClientSecret)
        }

        val request = HttpEntity(params, headers)
        val tokenResp = restTemplate.postForObject(tokenUrl, request, KakaoTokenResponse::class.java)
            ?: throw DomainException(ErrorCode.INTERNAL_ERROR)

        val userInfoUrl = "https://kapi.kakao.com/v2/user/me"
        val userHeaders = HttpHeaders().apply {
            setBearerAuth(tokenResp.accessToken)
            contentType = MediaType.APPLICATION_FORM_URLENCODED
        }
        val userEntity = HttpEntity<Unit>(userHeaders)

        val resp = restTemplate.exchange(userInfoUrl, HttpMethod.GET, userEntity, KakaoUserInfoResponse::class.java)
        val userInfo = resp.body ?: throw DomainException(ErrorCode.INTERNAL_ERROR)

        return SocialUserProfile(
            email = userInfo.kakaoAccount.email,
            nickname = userInfo.kakaoAccount.profile.nickname,
            profileImage = userInfo.kakaoAccount.profile.profileImageUrl
        )
    }

    // --- Naver ---
    private fun getNaverProfile(code: String): SocialUserProfile {
        val tokenUrl = "https://nid.naver.com/oauth2.0/token"
        // 네이버는 쿼리 파라미터로 전송 추천
        val uri = "$tokenUrl?grant_type=authorization_code&client_id=$naverClientId&client_secret=$naverClientSecret&code=$code&state=9999"

        val tokenResp = restTemplate.getForObject(uri, NaverTokenResponse::class.java)
            ?: throw DomainException(ErrorCode.INTERNAL_ERROR)

        val userInfoUrl = "https://openapi.naver.com/v1/nid/me"
        val headers = HttpHeaders().apply { setBearerAuth(tokenResp.accessToken) }
        val entity = HttpEntity<Unit>(headers)

        val resp = restTemplate.exchange(userInfoUrl, HttpMethod.GET, entity, NaverUserInfoResponse::class.java)
        val userInfo = resp.body?.response ?: throw DomainException(ErrorCode.INTERNAL_ERROR)

        return SocialUserProfile(
            email = userInfo.email,
            nickname = userInfo.name, // 네이버는 'name'이 실명, 'nickname'이 별명
            profileImage = userInfo.profileImage
        )
    }

    // --- DTOs for External APIs ---
    data class SocialUserProfile(val email: String, val nickname: String?, val profileImage: String?)

    // Google
    data class GoogleTokenResponse(@JsonProperty("access_token") val accessToken: String)
    data class GoogleUserInfo(val email: String, val name: String, val picture: String?)

    // Kakao
    data class KakaoTokenResponse(@JsonProperty("access_token") val accessToken: String)
    data class KakaoUserInfoResponse(@JsonProperty("kakao_account") val kakaoAccount: KakaoAccount)
    data class KakaoAccount(val email: String, val profile: KakaoProfile)
    data class KakaoProfile(val nickname: String, @JsonProperty("profile_image_url") val profileImageUrl: String?)

    // Naver
    data class NaverTokenResponse(@JsonProperty("access_token") val accessToken: String)
    data class NaverUserInfoResponse(val response: NaverUserDetail)
    data class NaverUserDetail(val email: String, val name: String?, @JsonProperty("profile_image") val profileImage: String?)
}
