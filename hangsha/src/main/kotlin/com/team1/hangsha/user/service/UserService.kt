package com.team1.hangsha.user.service

import com.team1.hangsha.user.dto.core.UserDto
import com.team1.hangsha.user.model.User
import com.team1.hangsha.user.repository.UserRepository
import com.team1.hangsha.user.JwtTokenProvider
import com.team1.hangsha.common.error.ErrorCode
import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.user.model.UserIdentity
import com.team1.hangsha.user.repository.UserIdentityRepository
import com.team1.hangsha.user.model.AuthProvider
import com.team1.hangsha.user.dto.IssuedTokens
import com.team1.hangsha.user.repository.RefreshTokenRepository
import com.team1.hangsha.common.upload.OciUploadService
import org.springframework.beans.factory.annotation.Value
import com.team1.hangsha.user.AuthCookieSupport
import com.team1.hangsha.user.model.RefreshToken
import com.team1.hangsha.user.TokenHasher
import com.fasterxml.jackson.databind.JsonNode
import com.team1.hangsha.common.extentions.getDisplayLength
import org.mindrot.jbcrypt.BCrypt
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Instant
import org.springframework.web.multipart.MultipartFile

@Service
class UserService(
    private val userRepository: UserRepository,
    private val jwtTokenProvider: JwtTokenProvider,
    private val userIdentityRepository: UserIdentityRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val tokenHasher: TokenHasher,
    private val cookieSupport: AuthCookieSupport,
    private val userPreferenceService: UserPreferenceService,
    private val ociUploadService: OciUploadService,
    @Value("\${jwt.refresh-expiration-ms}") private val refreshExpirationMs: Long,
) {

    private val log = LoggerFactory.getLogger(UserService::class.java)

    fun localRegister(
        email: String,
        password: String,
        username: String? = null
    ): UserDto {

        if (userIdentityRepository.existsByProviderAndEmail(AuthProvider.LOCAL, email)) {
            throw DomainException(ErrorCode.USER_EMAIL_ALREADY_EXISTS)
        }
        validatePassword(password)

        val encryptedPassword = BCrypt.hashpw(password, BCrypt.gensalt())
        val finalUsername = username?.takeIf { it.isNotBlank() } ?: email.substringBefore("@")
        val user =
            userRepository.save(
                User(
                    email = email,
                    username = finalUsername
                ),
            )

        userIdentityRepository.save(
            UserIdentity(
                userId = user.id!!,
                provider = AuthProvider.LOCAL,
                email = email,
                password = encryptedPassword,
            ),
        )

        return UserDto(user)
    }

    private fun validatePassword(password: String) {
        if (password.length < 8) {
            throw DomainException(ErrorCode.PASSWORD_TOO_SHORT)
        }
        if (password.contains("\\s".toRegex())) {
            throw DomainException(ErrorCode.PASSWORD_CONTAINS_WHITESPACE)
        }
        if (
            !password.any { it.isLetter() } ||
            !password.any { it.isDigit() } ||
            !password.any { !it.isLetterOrDigit() }
        ) {
            throw DomainException(ErrorCode.PASSWORD_WEAK)
        }
    }

    @Transactional
    fun issueAfterLocalLogin(email: String, password: String): IssuedTokens {
        val identity = userIdentityRepository.findByProviderAndEmail(AuthProvider.LOCAL, email)
            ?: throw DomainException(ErrorCode.AUTH_INVALID_CREDENTIALS)

        val hashed = identity.password ?: throw DomainException(ErrorCode.AUTH_INVALID_CREDENTIALS)
        if (!BCrypt.checkpw(password, hashed)) throw DomainException(ErrorCode.AUTH_INVALID_CREDENTIALS)

        val userId = identity.userId

        val access = jwtTokenProvider.createAccessToken(userId)
        val refresh = jwtTokenProvider.createRefreshToken(userId)

        saveRefresh(userId, refresh)

        val cookie = cookieSupport.buildRefreshCookie(
            token = refresh,
            maxAgeSeconds = refreshExpirationMs / 1000
        )

        return IssuedTokens(accessToken = access, refreshCookie = cookie)
    }

    @Transactional
    fun issueAfterSocialLogin(userId: Long): IssuedTokens {
        val access = jwtTokenProvider.createAccessToken(userId)
        val refresh = jwtTokenProvider.createRefreshToken(userId)

        saveRefresh(userId, refresh)

        val cookie = cookieSupport.buildRefreshCookie(
            token = refresh,
            maxAgeSeconds = refreshExpirationMs / 1000
        )

        return IssuedTokens(accessToken = access, refreshCookie = cookie)
    }

    @Transactional
    fun rotateAndIssueAccessToken(refreshToken: String): IssuedTokens {
        if (!jwtTokenProvider.validateRefreshToken(refreshToken)) {
            throw DomainException(ErrorCode.AUTH_INVALID_TOKEN)
        }

        val userId = jwtTokenProvider.getUserId(refreshToken)
        val jti = jwtTokenProvider.getJti(refreshToken)

        val row = refreshTokenRepository.findByJti(jti)
            ?: throw DomainException(ErrorCode.AUTH_INVALID_TOKEN)

        if (row.userId != userId) throw DomainException(ErrorCode.AUTH_INVALID_TOKEN)
        if (row.revokedAt != null) throw DomainException(ErrorCode.AUTH_INVALID_TOKEN)
        if (row.expiresAt.isBefore(Instant.now())) throw DomainException(ErrorCode.AUTH_TOKEN_EXPIRED)
        if (!tokenHasher.matches(refreshToken, row.tokenHash)) throw DomainException(ErrorCode.AUTH_INVALID_TOKEN)

        // rotation: 기존 refresh revoke
        val updated = refreshTokenRepository.revokeIfNotRevoked(jti)
        if (updated != 1) {
            throw DomainException(ErrorCode.AUTH_INVALID_TOKEN)
        }

        val newAccess = jwtTokenProvider.createAccessToken(userId)
        val newRefresh = jwtTokenProvider.createRefreshToken(userId)

        saveRefresh(userId, newRefresh)

        val cookie = cookieSupport.buildRefreshCookie(
            token = newRefresh,
            maxAgeSeconds = refreshExpirationMs / 1000
        )

        return IssuedTokens(accessToken = newAccess, refreshCookie = cookie)
    }

    @Transactional
    fun logout(refreshToken: String?) {
        if (refreshToken.isNullOrBlank()) return

        val jti = try {
            jwtTokenProvider.getJti(refreshToken)
        } catch (e: Exception) {
            log.debug("logout: invalid refresh token (cannot parse jti). ignore. err={}", e.toString())
            return
        }

        try {
            val row = refreshTokenRepository.findByJti(jti) ?: return
            if (row.revokedAt == null) {
                refreshTokenRepository.save(row.copy(revokedAt = Instant.now()))
            }
        } catch (e: Exception) {
            log.error("logout: failed to revoke refresh token in DB. jti={}", jti, e)
            return
        }
    }

    private fun saveRefresh(userId: Long, refreshToken: String) {
        val jti = jwtTokenProvider.getJti(refreshToken)
        val expiresAt = jwtTokenProvider.parseClaims(refreshToken).expiration.toInstant()

        refreshTokenRepository.save(
            RefreshToken(
                userId = userId,
                jti = jti,
                tokenHash = tokenHasher.hash(refreshToken),
                expiresAt = expiresAt,
            )
        )
    }

    fun updateProfile(userId: Long, body: JsonNode) {
        val hasUsername = body.has("username")
        val hasProfileImageUrl = body.has("profileImageUrl")

        if (!hasUsername && !hasProfileImageUrl) {
            throw DomainException(ErrorCode.INVALID_REQUEST)
        }

        val user = userRepository.findById(userId)
            .orElseThrow {
                DomainException(ErrorCode.USER_NOT_FOUND)
            }

        if (hasUsername) {
            val newUsername =
                if (body.get("username").isNull) null
                else body.get("username").asText()

            validateUsernameOrThrow(newUsername)

            user.username = newUsername
        }

        if (hasProfileImageUrl) {
            user.profileImageUrl =
                if (body.get("profileImageUrl").isNull) null
                else normalizeUrlOrNull(body.get("profileImageUrl").asText())
        }

        userRepository.save(user)
    }

    private fun validateUsernameOrThrow(username: String?) {
        val s = username?.trim() ?: return

        if (s.isBlank()) {
            throw DomainException(
                ErrorCode.INVALID_REQUEST,
                "username은 빈 문자열일 수 없습니다"
            )
        }

        if (s.getDisplayLength() > 15) {
            throw DomainException(
                ErrorCode.INVALID_REQUEST,
                "username은 50자를 초과할 수 없습니다"
            )
        }
    }

    private fun normalizeUrlOrNull(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isBlank()) return null

        return try {
            val uri = URI(s)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") throw IllegalArgumentException("invalid scheme")
            uri.toString()
        } catch (e: Exception) {
            throw DomainException(ErrorCode.INVALID_REQUEST, "profileImageUrl이 유효한 URL이 아닙니다")
        }
    }

    fun getMe(userId: Long): UserDto {
        val user = userRepository.findById(userId)
            .orElseThrow { DomainException(ErrorCode.USER_NOT_FOUND) }
        val interests = userPreferenceService.listInterestCategory(userId)

        return UserDto(user, interests)
    }

    fun updateProfileImageUrl(userId: Long, profileImageUrl: String) {
        val user = userRepository.findById(userId)
            .orElseThrow { DomainException(ErrorCode.USER_NOT_FOUND) }

        user.profileImageUrl = profileImageUrl
        userRepository.save(user)
    }

    fun uploadProfile(userId: Long, file: MultipartFile): String {
        val contentType = file.contentType ?: ""
        if (!contentType.startsWith("image/")) {
            throw DomainException(ErrorCode.UPLOAD_UNSUPPORTED_MEDIA_TYPE, "이미지 파일만 업로드할 수 있습니다")
        }

        return ociUploadService.uploadFile(
            prefix = "uploads/users/$userId",
            file = file,
        )
    }
}
