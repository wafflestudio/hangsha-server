package com.team1.hangsha

import com.team1.hangsha.board.dto.CreateCommentRequest
import com.team1.hangsha.board.dto.CreatePostRequest
import com.team1.hangsha.board.service.BoardPolicy
import com.team1.hangsha.common.error.DomainException
import jakarta.validation.Validation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardPolicyTest {

    @Test
    fun `text limits trim and one character content`() {
        assertEquals("a", BoardPolicy.text(" a ", BoardPolicy.CONTENT_MAX, "본문"))
        listOf(
            BoardPolicy.TITLE_MAX,
            BoardPolicy.CONTENT_MAX,
            BoardPolicy.COMMENT_MAX,
            BoardPolicy.TAG_MAX
        ).forEach { max ->
            assertEquals(max, BoardPolicy.text("a".repeat(max), max, "입력").length)
            assertFailsWith<DomainException> { BoardPolicy.text("a".repeat(max + 1), max, "입력") }
            assertFailsWith<DomainException> { BoardPolicy.text(" \n\t", max, "입력") }
        }
    }

    @Test
    fun `tag limits and normalized duplicates`() {
        assertEquals(listOf("a", "b"), BoardPolicy.tags(listOf(" a ", "a", "b")))
        assertEquals(5, BoardPolicy.tags((1..5).map { "$it" }).size)
        assertFailsWith<DomainException> { BoardPolicy.tags((1..6).map { "$it" }) }
        assertFailsWith<DomainException> { BoardPolicy.tags(listOf(" ")) }
    }

    @Test
    fun `IDs pages large offsets search`() {
        assertEquals(214748364600L, BoardPolicy.page(Int.MAX_VALUE, 100))
        assertFailsWith<DomainException> { BoardPolicy.page(0, 20) }
        assertFailsWith<DomainException> { BoardPolicy.page(1, 101) }
        assertFailsWith<DomainException> { BoardPolicy.id(0) }
        assertNull(BoardPolicy.query(" "))
        assertEquals("text", BoardPolicy.query(" text "))
        assertFailsWith<DomainException> { BoardPolicy.query("a".repeat(101)) }
    }

    @Test
    fun `creation DTO validation covers blank fields limits IDs and tag elements`() {
        Validation.buildDefaultValidatorFactory().use { factory ->
            val validator = factory.validator
            val valid = CreatePostRequest("제목", "a", tagNames = listOf("태그"))
            assertTrue(validator.validate(valid).isEmpty())
            listOf(
                valid.copy(title = " "), valid.copy(title = "a".repeat(101)),
                valid.copy(content = ""), valid.copy(content = "a".repeat(10001)),
                valid.copy(eventId = 0), valid.copy(tagNames = List(6) { "태그" }),
                valid.copy(tagNames = listOf(" ")), valid.copy(tagNames = listOf("a".repeat(21))),
            ).forEach { assertTrue(validator.validate(it).isNotEmpty(), it.toString().take(100)) }
            assertTrue(validator.validate(CreateCommentRequest("a")).isEmpty())
            listOf(
                CreateCommentRequest(" "),
                CreateCommentRequest("a".repeat(1001)),
                CreateCommentRequest("a", parentId = 0),
            ).forEach { assertTrue(validator.validate(it).isNotEmpty()) }
        }
    }
}
