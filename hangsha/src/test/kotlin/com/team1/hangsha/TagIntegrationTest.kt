package com.team1.hangsha

import com.team1.hangsha.helper.IntegrationTestBase
import com.team1.hangsha.tag.repository.TagRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not

class TagIntegrationTest : IntegrationTestBase() {

    @Autowired lateinit var tagRepository: TagRepository

    private fun createBody(name: String) = toJson(mapOf("name" to name))

    // =========================================================
    // 태그 조회
    // =========================================================

    @Test
    fun `태그 조회 로그인한 유저는 본인 태그만 전부 반환한다`() {
        val (userA, tokenA) = dataGenerator.generateUserWithAccessToken()
        val (userB, tokenB) = dataGenerator.generateUserWithAccessToken()

        val userAId = requireNotNull(userA.id)
        val userBId = requireNotNull(userB.id)

        val a1 = dataGenerator.generateTag(userId = userAId, name = "A1")
        val a2 = dataGenerator.generateTag(userId = userAId, name = "A2")
        dataGenerator.generateTag(userId = userBId, name = "B1")

        val res = mockMvc.perform(
            get("/api/v1/tags")
                .header("Authorization", bearer(tokenA))
        )
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.items").isArray)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andReturn()

        val root = objectMapper.readTree(res.response.contentAsString)
        val items = root["items"]
        val ids = items.map { it["id"].asLong() }.toSet()
        val names = items.map { it["name"].asText() }.toSet()

        assertEquals(setOf(requireNotNull(a1.id), requireNotNull(a2.id)), ids)
        assertEquals(setOf("A1", "A2"), names)

        mockMvc.perform(
            get("/api/v1/tags")
                .header("Authorization", bearer(tokenB))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].name").value("B1"))
    }

    @Test
    fun `태그 조회 토큰 없으면 401`() {
        mockMvc.perform(get("/api/v1/tags"))
            .andExpect(status().isUnauthorized)
    }

    // =========================================================
    // 태그 생성
    // =========================================================

    @Test
    fun `태그 생성 성공하면 200 이고 DB에 저장된다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val userId = requireNotNull(user.id)

        val res = mockMvc.perform(
            post("/api/v1/tags")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("new-tag"))
        )
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").isNumber)
            .andExpect(jsonPath("$.name").value("new-tag"))
            .andReturn()

        val createdId = objectMapper.readTree(res.response.contentAsString)["id"].asLong()
        val saved = tagRepository.findById(createdId).orElseThrow()
        assertEquals(userId, saved.userId)
        assertEquals("new-tag", saved.name)
    }

    @Test
    fun `태그 생성 같은 유저가 같은 이름으로 두 번 만들면 409`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val userId = requireNotNull(user.id)

        dataGenerator.generateTag(userId = userId, name = "dup")

        mockMvc.perform(
            post("/api/v1/tags")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("dup"))
        )
            .andExpect(status().isConflict)
    }

    @Test
    fun `태그 생성 다른 유저면 같은 이름도 만들 수 있다`() {
        val (userA, _) = dataGenerator.generateUserWithAccessToken()
        val (_, tokenB) = dataGenerator.generateUserWithAccessToken()

        dataGenerator.generateTag(userId = requireNotNull(userA.id), name = "same")

        mockMvc.perform(
            post("/api/v1/tags")
                .header("Authorization", bearer(tokenB))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("same"))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("same"))
    }

    @Test
    fun `태그 생성 토큰 없으면 401`() {
        mockMvc.perform(
            post("/api/v1/tags")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("x"))
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `태그 생성 이름이 빈 문자열이면 400이고 저장되지 않는다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val userId = requireNotNull(user.id)

        mockMvc.perform(
            post("/api/v1/tags")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(""))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("TAG_NAME_CANNOT_BE_BLANK"))

        assertTrue(tagRepository.findAllByUserId(userId).isEmpty())
    }

    // =========================================================
    // 태그 수정
    // =========================================================

    @Test
    fun `태그 수정 성공하면 200 이고 DB에도 반영된다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val userId = requireNotNull(user.id)

        val tag = dataGenerator.generateTag(userId = userId, name = "old")
        val tagId = requireNotNull(tag.id)

        mockMvc.perform(
            patch("/api/v1/tags/$tagId")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("new"))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(tagId))
            .andExpect(jsonPath("$.name").value("new"))

        val saved = tagRepository.findById(tagId).orElseThrow()
        assertEquals("new", saved.name)
    }

    @Test
    fun `태그 수정 없는 태그면 404`() {
        val (_, token) = dataGenerator.generateUserWithAccessToken()

        mockMvc.perform(
            patch("/api/v1/tags/999999999")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("x"))
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `태그 수정 남의 태그를 수정하면 404 이고 DB는 변하지 않는다`() {
        val (userA, _) = dataGenerator.generateUserWithAccessToken()
        val (_, tokenB) = dataGenerator.generateUserWithAccessToken()

        val tag = dataGenerator.generateTag(userId = requireNotNull(userA.id), name = "mine")
        val tagId = requireNotNull(tag.id)

        mockMvc.perform(
            patch("/api/v1/tags/$tagId")
                .header("Authorization", bearer(tokenB))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("hacked"))
        )
            .andExpect(status().isNotFound)

        val saved = tagRepository.findById(tagId).orElseThrow()
        assertEquals("mine", saved.name)
    }

    @Test
    fun `태그 수정 같은 유저 내에서 이름 중복으로 바꾸면 409`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val userId = requireNotNull(user.id)

        val t1 = dataGenerator.generateTag(userId = userId, name = "t1")
        dataGenerator.generateTag(userId = userId, name = "t2")

        mockMvc.perform(
            patch("/api/v1/tags/${requireNotNull(t1.id)}")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("t2"))
        )
            .andExpect(status().isConflict)
    }

    @Test
    fun `태그 수정 이름이 빈 문자열이면 400이고 DB는 변하지 않는다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val userId = requireNotNull(user.id)

        val tag = dataGenerator.generateTag(userId = userId, name = "old")
        val tagId = requireNotNull(tag.id)

        mockMvc.perform(
            patch("/api/v1/tags/$tagId")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(""))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("TAG_NAME_CANNOT_BE_BLANK"))

        val saved = tagRepository.findById(tagId).orElseThrow()
        assertEquals("old", saved.name)
    }

    @Test
    fun `태그 수정 서로 다른 유저가 같은 이름 태그를 가진 경우 한 명이 수정해도 다른 유저 태그는 영향 없다`() {
        val (userA, tokenA) = dataGenerator.generateUserWithAccessToken()
        val (userB, tokenB) = dataGenerator.generateUserWithAccessToken()

        val userAId = requireNotNull(userA.id)
        val userBId = requireNotNull(userB.id)

        // A와 B가 동일한 이름의 태그를 각각 보유
        val tagA = dataGenerator.generateTag(userId = userAId, name = "same")
        val tagB = dataGenerator.generateTag(userId = userBId, name = "same")

        val tagAId = requireNotNull(tagA.id)
        val tagBId = requireNotNull(tagB.id)

        // A가 본인 태그를 "changed"로 변경
        mockMvc.perform(
            patch("/api/v1/tags/$tagAId")
                .header("Authorization", bearer(tokenA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("changed"))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(tagAId))
            .andExpect(jsonPath("$.name").value("changed"))

        // DB 검증: A만 변경, B는 그대로 "same"
        val savedA = tagRepository.findById(tagAId).orElseThrow()
        val savedB = tagRepository.findById(tagBId).orElseThrow()

        assertEquals(userAId, savedA.userId)
        assertEquals("changed", savedA.name)

        assertEquals(userBId, savedB.userId)
        assertEquals("same", savedB.name)

        // (선택) B가 자기 토큰으로 조회하면 여전히 "same"만 보이는지 확인
        mockMvc.perform(
            get("/api/v1/tags")
                .header("Authorization", bearer(tokenB))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[*].name", hasItem("same")))
            .andExpect(jsonPath("$.items[*].name", not(hasItem("changed"))))
    }

    @Test
    fun `태그 수정 토큰 없으면 401`() {
        mockMvc.perform(
            patch("/api/v1/tags/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("x"))
        )
            .andExpect(status().isUnauthorized)
    }

    // =========================================================
    // 태그 삭제
    // =========================================================

    @Test
    fun `태그 삭제 성공하면 204 이고 DB에서도 삭제된다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val userId = requireNotNull(user.id)

        val tag = dataGenerator.generateTag(userId = userId, name = "to-delete")
        val tagId = requireNotNull(tag.id)

        mockMvc.perform(
            delete("/api/v1/tags/$tagId")
                .header("Authorization", bearer(token))
        )
            .andExpect(status().isNoContent)

        assertTrue(tagRepository.findById(tagId).isEmpty)
    }

    @Test
    fun `태그 삭제 서로 다른 유저가 같은 이름 태그를 가진 경우 한 명이 삭제해도 다른 유저 태그는 영향 없다`() {
        val (userA, tokenA) = dataGenerator.generateUserWithAccessToken()
        val (userB, tokenB) = dataGenerator.generateUserWithAccessToken()

        val userAId = requireNotNull(userA.id)
        val userBId = requireNotNull(userB.id)

        // A와 B가 동일한 이름의 태그를 각각 보유
        val tagA = dataGenerator.generateTag(userId = userAId, name = "same")
        val tagB = dataGenerator.generateTag(userId = userBId, name = "same")

        val tagAId = requireNotNull(tagA.id)
        val tagBId = requireNotNull(tagB.id)

        // A가 본인 태그 삭제
        mockMvc.perform(
            delete("/api/v1/tags/$tagAId")
                .header("Authorization", bearer(tokenA))
        )
            .andExpect(status().isNoContent)

        // DB 검증
        assertTrue(tagRepository.findById(tagAId).isEmpty)   // A는 삭제됨
        val savedB = tagRepository.findById(tagBId).orElseThrow()
        assertEquals("same", savedB.name)                    // B는 그대로

        // B로 조회하면 여전히 same 태그만 보임
        mockMvc.perform(
            get("/api/v1/tags")
                .header("Authorization", bearer(tokenB))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[*].name", hasItem("same")))
    }

    @Test
    fun `태그 삭제 남의 태그를 삭제하면 404 이고 DB는 그대로다`() {
        val (userA, _) = dataGenerator.generateUserWithAccessToken()
        val (_, tokenB) = dataGenerator.generateUserWithAccessToken()

        val tag = dataGenerator.generateTag(userId = requireNotNull(userA.id), name = "mine")
        val tagId = requireNotNull(tag.id)

        mockMvc.perform(
            delete("/api/v1/tags/$tagId")
                .header("Authorization", bearer(tokenB))
        )
            .andExpect(status().isNotFound)

        assertTrue(tagRepository.findById(tagId).isPresent)
    }

    @Test
    fun `태그 삭제 없는 태그면 404`() {
        val (_, token) = dataGenerator.generateUserWithAccessToken()

        mockMvc.perform(
            delete("/api/v1/tags/999999999")
                .header("Authorization", bearer(token))
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `태그 삭제 토큰 없으면 401`() {
        mockMvc.perform(delete("/api/v1/tags/1"))
            .andExpect(status().isUnauthorized)
    }
}
