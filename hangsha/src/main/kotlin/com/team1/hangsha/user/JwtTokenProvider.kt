package com.team1.hangsha.user

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Date
import java.util.UUID


@Component
class JwtTokenProvider(
    @Value("\${jwt.secret}")
    secretKey: String,

    @Value("\${jwt.access-expiration-ms}")
    private val accessExpirationMs: Long,

    @Value("\${jwt.refresh-expiration-ms}")
    private val refreshExpirationMs: Long,
) {

    private val key = Keys.hmacShaKeyFor(secretKey.toByteArray())

    fun createAccessToken(userId: Long, isAdmin: Boolean): String {
        return createToken(
            userId = userId,
            isAdmin = isAdmin,
            expirationMs = accessExpirationMs,
            type = "ACCESS",
        )
    }

    fun createRefreshToken(userId: Long): String {
        return createToken(
            userId = userId,
            expirationMs = refreshExpirationMs,
            type = "REFRESH",
        )
    }

    fun createToken(
        userId: Long,
        isAdmin: Boolean,
        expirationMs: Long,
        type: String
    ): String {
        val now = Date()
        val expiry = Date(now.time + expirationMs)

        val jti = UUID.randomUUID().toString()

        return Jwts.builder()
            .setSubject(userId.toString())
            .setId(jti)
            .claim("jti", jti)
            .claim("type", type)
            .claim("isAdmin", isAdmin)
            .setIssuedAt(now)
            .setExpiration(expiry)
            .signWith(key, SignatureAlgorithm.HS256)
            .compact()
    }

    fun parseClaims(token: String) =
        Jwts.parserBuilder()
            .setSigningKey(key)
            .build()
            .parseClaimsJws(token)
            .body

    fun getUserId(token: String): Long =
        parseClaims(token).subject.toLong()

    fun getIsAdmin(token: String): Boolean =
        parseClaims(token)["isAdmin"] as? Boolean ?: false

    fun validateAccessToken(token: String): Boolean {
        try {
            val claims = Jwts
                .parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .body

            // type 체크 (ACCESS 토큰만 인증용 허용)
            val type = claims["type"] as? String
            if (type != "ACCESS") {
                return false
            }

            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun validateRefreshToken(token: String): Boolean {
        try {
            val claims = Jwts
                .parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .body

            val type = claims["type"] as? String
            if (type != "REFRESH") {
                return false
            }

            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun getJti(token: String): String =
        parseClaims(token).id
}
