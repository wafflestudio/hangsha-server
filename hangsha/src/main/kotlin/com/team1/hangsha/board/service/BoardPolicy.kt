package com.team1.hangsha.board.service

import com.fasterxml.jackson.databind.JsonNode
import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode

object BoardPolicy {
    const val TITLE_MAX = 100
    const val CONTENT_MAX = 10_000
    const val COMMENT_MAX = 1_000
    const val TAG_MAX = 20
    const val TAG_COUNT_MAX = 5
    const val PAGE_SIZE_MAX = 100
    const val POPULAR_LIKES = 10

    const val ANONYMOUS_AUTHOR_NAME = "익명"
    const val ANONYMOUS_POST_AUTHOR_NAME = "익명(글쓴이)"
    const val WITHDRAWN_AUTHOR_NAME = "(탈퇴됨)"
    const val DEFAULT_AUTHOR_NAME = "사용자"
    const val DELETED_COMMENT_CONTENT = "(삭제)"

    fun invalid(message: String): Nothing = throw DomainException(ErrorCode.INVALID_REQUEST, message)

    fun id(id: Long) {
        if (id <= 0) invalid("ID는 양의 정수여야 합니다")
    }

    fun size(size: Int) {
        if (size !in 1..PAGE_SIZE_MAX) invalid("size는 1~100이어야 합니다")
    }

    fun page(page: Int, size: Int): Long {
        size(size)
        if (page < 1) invalid("page는 1 이상이어야 합니다")
        return (page.toLong() - 1) * size
    }

    fun text(value: String, max: Int, label: String): String = value.trim().also {
        if (it.isEmpty() || it.length > max) invalid("${label}은 1~${max}자여야 합니다")
    }

    fun tags(names: List<String>): List<String> {
        if (names.size > TAG_COUNT_MAX) invalid("태그는 최대 5개입니다")
        return names.map { text(it, TAG_MAX, "태그") }.distinct()
    }

    fun query(query: String?): String? = query?.trim()?.also {
        if (it.length > 100) invalid("검색어는 최대 100자입니다")
    }?.takeIf { it.isNotEmpty() }

    // PATCH needs to distinguish omitted fields from explicit null.
    fun body(body: JsonNode, allowed: Set<String>) {
        if (!body.isObject || body.fieldNames().asSequence().any { it !in allowed }) {
            invalid("허용되지 않은 요청 필드입니다")
        }
    }

    fun string(body: JsonNode, field: String): String {
        val value = body[field]
        if (value == null || !value.isTextual) invalid("${field}는 문자열이어야 합니다")
        return value.textValue()
    }

    fun flag(body: JsonNode, field: String): Boolean {
        if (!body.has(field)) return false
        if (!body[field].isBoolean) invalid("${field}는 boolean이어야 합니다")
        return body[field].booleanValue()
    }

    fun tagNames(body: JsonNode, allowNull: Boolean = false): List<String> {
        val value = body["tagNames"] ?: return emptyList()
        if (allowNull && value.isNull) return emptyList()
        if (!value.isArray || value.any { !it.isTextual }) invalid("tagNames는 문자열 배열이어야 합니다")
        return tags(value.map { it.textValue() })
    }

}
