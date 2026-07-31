package com.team1.hangsha

import com.team1.hangsha.helper.IntegrationTestBase
import com.team1.hangsha.memo.repository.MemoRepository
import com.team1.hangsha.tag.repository.TagRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

class MemoTagIntegrationTest : IntegrationTestBase() {

    @Autowired lateinit var memoRepository: MemoRepository
    @Autowired lateinit var tagRepository: TagRepository

    private fun findMemosByTagPath(tagId: Long): String = "/api/v1/memos/by-tag/$tagId"

    private fun createBody(eventId: Long, content: String, tagNames: List<String> = emptyList()): String =
        toJson(mapOf("eventId" to eventId, "content" to content, "tagNames" to tagNames))

    private fun listMyMemos(token: String) =
        mockMvc.perform(
            get("/api/v1/memos")
                .header("Authorization", bearer(token))
        )

    private fun listMemosByTag(token: String, tagId: Long) =
        mockMvc.perform(
            get(findMemosByTagPath(tagId))
                .header("Authorization", bearer(token))
        )

    private fun getMemoByEvent(token: String, eventId: Long) =
        mockMvc.perform(
            get("/api/v1/memos/by-event/$eventId")
                .header("Authorization", bearer(token))
        )

    // =========================================================
    // POST /api/v1/memos  메모 생성
    // =========================================================

    @Test
    fun `POST memos 메모 생성 태그가 없으면 태그를 생성하고 조인 테이블이 연결된다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val userId = requireNotNull(user.id)

        val event = dataGenerator.generateEvent(title = "EV1")
        val eventId = requireNotNull(event.id)

        val res = mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(eventId, "memo-content", listOf("t1", "t2")))
        )
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").isNumber)
            .andExpect(jsonPath("$.eventId").value(eventId))
            .andExpect(jsonPath("$.eventTitle").value("EV1"))
            .andExpect(jsonPath("$.content").value("memo-content"))
            .andExpect(jsonPath("$.tags").isArray)
            .andReturn()

        val memoId = objectMapper.readTree(res.response.contentAsString)["id"].asLong()

        // Memo 저장 확인 + memo_tags 연결 확인 (Spring Data JDBC aggregate 로딩)
        val saved = memoRepository.findById(memoId).orElseThrow()
        assertEquals(userId, saved.userId)
        assertEquals(eventId, saved.eventId)
        assertEquals("memo-content", saved.content)
        assertEquals(2, saved.tags.size)

        // Tag 테이블에도 생성됐는지 (user scope)
        val t1 = tagRepository.findByUserIdAndName(userId, "t1").orElseThrow()
        val t2 = tagRepository.findByUserIdAndName(userId, "t2").orElseThrow()

        val tagIdsInMemo = saved.tags.map { it.tagId }.toSet()
        assertEquals(setOf(requireNotNull(t1.id), requireNotNull(t2.id)), tagIdsInMemo)
    }

    @Test
    fun `POST memos 메모 생성 같은 유저가 같은 태그 이름을 또 쓰면 태그는 재사용된다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val userId = requireNotNull(user.id)

        val event1 = dataGenerator.generateEvent(title = "EV1")
        val event2 = dataGenerator.generateEvent(title = "EV2")

        val e1 = requireNotNull(event1.id)
        val e2 = requireNotNull(event2.id)

        // 첫 메모 생성 -> 태그 생성
        val res1 = mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(e1, "c1", listOf("same-tag")))
        )
            .andExpect(status().isOk)
            .andReturn()

        val memoId1 = objectMapper.readTree(res1.response.contentAsString)["id"].asLong()

        // 두 번째 메모 생성 -> 같은 태그 재사용 기대
        val res2 = mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(e2, "c2", listOf("same-tag")))
        )
            .andExpect(status().isOk)
            .andReturn()

        val memoId2 = objectMapper.readTree(res2.response.contentAsString)["id"].asLong()

        val tag = tagRepository.findByUserIdAndName(userId, "same-tag").orElseThrow()
        val tagId = requireNotNull(tag.id)

        val m1 = memoRepository.findById(memoId1).orElseThrow()
        val m2 = memoRepository.findById(memoId2).orElseThrow()

        assertTrue(m1.tags.any { it.tagId == tagId })
        assertTrue(m2.tags.any { it.tagId == tagId })

        // 같은 이름/유저로 조회했을 때 1개만 조회되는 것으로 대체 검증
        assertEquals(tagId, requireNotNull(tagRepository.findByUserIdAndName(userId, "same-tag").orElseThrow().id))
    }

    @Test
    fun `POST memos 내용만 보내면 메모가 생성되고 태그는 비어있다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val userId = requireNotNull(user.id)

        val event = dataGenerator.generateEvent(title = "EV")
        val eventId = requireNotNull(event.id)

        val res = mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    toJson(
                        mapOf(
                            "eventId" to eventId,
                            "content" to "memo only"
                            // tagNames 미포함
                        )
                    )
                )
        )
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").isNumber)
            .andExpect(jsonPath("$.eventId").value(eventId))
            .andExpect(jsonPath("$.content").value("memo only"))
            .andExpect(jsonPath("$.tags").isArray)
            .andExpect(jsonPath("$.tags.length()").value(0))
            .andReturn()

        val memoId = objectMapper.readTree(res.response.contentAsString)["id"].asLong()
        val saved = memoRepository.findById(memoId).orElseThrow()

        assertEquals(userId, saved.userId)
        assertEquals(eventId, saved.eventId)
        assertEquals("memo only", saved.content)
        assertEquals(0, saved.tags.size)
    }

    @Test
    fun `POST memos content 필드가 없고 tagNames만 있으면 400이고 tags도 저장되지 않는다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val userId = requireNotNull(user.id)

        val event = dataGenerator.generateEvent(title = "EV")
        val eventId = requireNotNull(event.id)

        assertTrue(tagRepository.findByUserIdAndName(userId, "onlyTag").isEmpty)

        mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    toJson(
                        mapOf(
                            "eventId" to eventId,
                            // "content" 미포함
                            "tagNames" to listOf("onlyTag")
                        )
                    )
                )
        )
            .andExpect(status().isBadRequest)

        assertTrue(tagRepository.findByUserIdAndName(userId, "onlyTag").isEmpty)
    }

    @Test
    fun `POST memos content가 빈 문자열이면 400이고 저장되지 않는다`() {
        val (_, token) = dataGenerator.generateUserWithAccessToken()
        val eventId = requireNotNull(dataGenerator.generateEvent(title = "EV").id)

        mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(eventId, ""))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("MEMO_CONTENT_CANNOT_BE_BLANK"))

        assertTrue(memoRepository.findAll().none())
    }

    @Test
    fun `POST memos 메모 생성 없는 이벤트면 404`() {
        val (_, token) = dataGenerator.generateUserWithAccessToken()

        mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(eventId = 9_999_999_999L, content = "x", tagNames = listOf("t")))
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `POST memos 메모 생성 토큰 없으면 401`() {
        val event = dataGenerator.generateEvent(title = "EV1")
        val eventId = requireNotNull(event.id)

        mockMvc.perform(
            post("/api/v1/memos")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(eventId, "x", listOf("t")))
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `POST memos tagNames에 중복이 있어도 태그는 한번만 연결된다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val userId = requireNotNull(user.id)

        val eventId = requireNotNull(
            dataGenerator.generateEvent(title = "EV").id
        )

        val res = mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    toJson(
                        mapOf(
                            "eventId" to eventId,
                            "content" to "memo",
                            "tagNames" to listOf("dup", "dup", "dup")
                        )
                    )
                )
        )
            .andExpect(status().isOk)
            .andReturn()

        val memoId = objectMapper.readTree(res.response.contentAsString)["id"].asLong()

        val memo = memoRepository.findById(memoId).orElseThrow()

        // memo에는 tag 1개만 연결
        assertEquals(1, memo.tags.size)

        val tag = tagRepository.findByUserIdAndName(userId, "dup").orElseThrow()

        assertEquals(
            setOf(requireNotNull(tag.id)),
            memo.tags.map { it.tagId }.toSet()
        )
    }

    // =========================================================
    // GET /api/v1/memos  내 메모 조회
    // =========================================================

    @Test
    fun `GET memos 내 메모만 조회되고 생성일 내림차순이다`() {
        val (userA, tokenA) = dataGenerator.generateUserWithAccessToken()
        val (userB, tokenB) = dataGenerator.generateUserWithAccessToken()

        val e = dataGenerator.generateEvent(title = "EV")

        mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(tokenA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(requireNotNull(e.id), "A1", listOf("t1")))
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(tokenA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(requireNotNull(e.id), "A2", listOf("t2")))
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(tokenB))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(requireNotNull(e.id), "B1", listOf("tb")))
        ).andExpect(status().isOk)

        val resA = listMyMemos(tokenA)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.memos").isArray)
            .andExpect(jsonPath("$.memos.length()").value(2))
            .andReturn()

        val rootA = objectMapper.readTree(resA.response.contentAsString)
        val contentsA = rootA["memos"].map { it["content"].asText() }
        assertEquals(listOf("A2", "A1"), contentsA)

        val resB = listMyMemos(tokenB)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.memos.length()").value(1))
            .andReturn()

        val rootB = objectMapper.readTree(resB.response.contentAsString)
        val contentsB = rootB["memos"].map { it["content"].asText() }
        assertEquals(listOf("B1"), contentsB)
    }

    @Test
    fun `GET memos 토큰 없으면 401`() {
        mockMvc.perform(get("/api/v1/memos"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET memos by event 내 특정 이벤트 메모를 단일 응답으로 조회한다`() {
        val (_, token) = dataGenerator.generateUserWithAccessToken()
        val event = dataGenerator.generateEvent(title = "Target Event")
        val eventId = requireNotNull(event.id)

        val created = mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(eventId, "event memo", listOf("event-tag")))
        )
            .andExpect(status().isOk)
            .andReturn()

        val memoId = objectMapper.readTree(created.response.contentAsString)["id"].asLong()

        getMemoByEvent(token, eventId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(memoId))
            .andExpect(jsonPath("$.eventId").value(eventId))
            .andExpect(jsonPath("$.eventTitle").value("Target Event"))
            .andExpect(jsonPath("$.content").value("event memo"))
            .andExpect(jsonPath("$.tags").isArray)
    }

    @Test
    fun `GET memos by event 남의 메모거나 없으면 404`() {
        val (_, tokenA) = dataGenerator.generateUserWithAccessToken()
        val (_, tokenB) = dataGenerator.generateUserWithAccessToken()
        val eventId = requireNotNull(dataGenerator.generateEvent(title = "EV").id)

        mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(tokenA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(eventId, "mine"))
        )
            .andExpect(status().isOk)

        getMemoByEvent(tokenB, eventId)
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET memos by tag 중복 tagNames로 생성된 메모도 정상 조회된다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val userId = requireNotNull(user.id)

        val eventId = requireNotNull(
            dataGenerator.generateEvent(title = "EV").id
        )

        val created = mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    toJson(
                        mapOf(
                            "eventId" to eventId,
                            "content" to "memo",
                            "tagNames" to listOf("dup", "dup")
                        )
                    )
                )
        ).andReturn()

        val memoId = objectMapper.readTree(created.response.contentAsString)["id"].asLong()

        val tagId = requireNotNull(
            tagRepository.findByUserIdAndName(userId, "dup").orElseThrow().id
        )

        val res = mockMvc.perform(
            get("/api/v1/memos/by-tag/$tagId")
                .header("Authorization", bearer(token))
        )
            .andExpect(status().isOk)
            .andReturn()

        val ids = objectMapper
            .readTree(res.response.contentAsString)["memos"]
            .map { it["id"].asLong() }
            .toSet()

        assertTrue(ids.contains(memoId))
    }

    // =========================================================
    // PATCH /api/v1/memos/{memoId}  메모 수정
    // 정책: 필드 미포함=변경 없음 / null=비우기
    // =========================================================

    @Test
    fun `PATCH memos 메모 수정 content 필드 미포함이면 내용은 변경되지 않는다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val userId = requireNotNull(user.id)

        val eventId = requireNotNull(dataGenerator.generateEvent(title = "EV").id)

        val created = mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(eventId, "old", listOf("t1", "t2")))
        ).andExpect(status().isOk).andReturn()

        val memoId = objectMapper.readTree(created.response.contentAsString)["id"].asLong()

        val patchBody = toJson(mapOf("tagNames" to listOf("t2", "t3")))

        mockMvc.perform(
            patch("/api/v1/memos/$memoId")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").value("old"))
            .andExpect(jsonPath("$.tags.length()").value(2))

        val saved = memoRepository.findById(memoId).orElseThrow()
        assertEquals("old", saved.content)

        val t2 = tagRepository.findByUserIdAndName(userId, "t2").orElseThrow()
        val t3 = tagRepository.findByUserIdAndName(userId, "t3").orElseThrow()
        assertEquals(setOf(requireNotNull(t2.id), requireNotNull(t3.id)), saved.tags.map { it.tagId }.toSet())
    }

    @Test
    fun `PATCH memos 메모 수정 tagNames 필드 미포함이면 태그는 변경되지 않는다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val userId = requireNotNull(user.id)

        val eventId = requireNotNull(dataGenerator.generateEvent(title = "EV").id)

        val created = mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(eventId, "old", listOf("t1", "t2")))
        ).andExpect(status().isOk).andReturn()

        val memoId = objectMapper.readTree(created.response.contentAsString)["id"].asLong()

        val patchBody = toJson(mapOf("content" to "new"))

        mockMvc.perform(
            patch("/api/v1/memos/$memoId")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").value("new"))
            .andExpect(jsonPath("$.tags.length()").value(2))

        val saved = memoRepository.findById(memoId).orElseThrow()
        assertEquals("new", saved.content)

        val t1 = tagRepository.findByUserIdAndName(userId, "t1").orElseThrow()
        val t2 = tagRepository.findByUserIdAndName(userId, "t2").orElseThrow()
        assertEquals(setOf(requireNotNull(t1.id), requireNotNull(t2.id)), saved.tags.map { it.tagId }.toSet())
    }

    @Test
    fun `PATCH memos 메모 수정 tagNames가 null이면 태그가 전부 비워진다`() {
        val (_, token) = dataGenerator.generateUserWithAccessToken()

        val eventId = requireNotNull(dataGenerator.generateEvent(title = "EV").id)

        val created = mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(eventId, "content", listOf("t1", "t2")))
        ).andExpect(status().isOk).andReturn()

        val memoId = objectMapper.readTree(created.response.contentAsString)["id"].asLong()

        val patchBody = """{"tagNames":null}"""

        mockMvc.perform(
            patch("/api/v1/memos/$memoId")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tags").isArray)
            .andExpect(jsonPath("$.tags.length()").value(0))

        val saved = memoRepository.findById(memoId).orElseThrow()
        assertEquals(0, saved.tags.size)
    }

    @Test
    fun `PATCH memos 메모 수정 content가 null이면 400이고 DB는 변하지 않는다`() {
        val (_, token) = dataGenerator.generateUserWithAccessToken()

        val eventId = requireNotNull(dataGenerator.generateEvent(title = "EV").id)

        val created = mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(eventId, "will-clear", listOf("t1")))
        ).andExpect(status().isOk).andReturn()

        val memoId = objectMapper.readTree(created.response.contentAsString)["id"].asLong()

        val patchBody = """{"content":null}"""

        mockMvc.perform(
            patch("/api/v1/memos/$memoId")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(patchBody)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("MEMO_CONTENT_CANNOT_BE_BLANK"))

        val saved = memoRepository.findById(memoId).orElseThrow()
        assertEquals("will-clear", saved.content)
    }

    @Test
    fun `PATCH memos 메모 수정 content가 빈 문자열이면 400이고 DB는 변하지 않는다`() {
        val (_, token) = dataGenerator.generateUserWithAccessToken()

        val eventId = requireNotNull(dataGenerator.generateEvent(title = "EV").id)

        val created = mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(eventId, "old", listOf("t1")))
        ).andExpect(status().isOk).andReturn()

        val memoId = objectMapper.readTree(created.response.contentAsString)["id"].asLong()

        mockMvc.perform(
            patch("/api/v1/memos/$memoId")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(mapOf("content" to "")))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("MEMO_CONTENT_CANNOT_BE_BLANK"))

        val saved = memoRepository.findById(memoId).orElseThrow()
        assertEquals("old", saved.content)
        assertEquals(1, saved.tags.size)
    }

    @Test
    fun `PATCH memos 메모 수정 남의 메모면 404이고 DB는 변하지 않는다`() {
        val (userA, tokenA) = dataGenerator.generateUserWithAccessToken()
        val (_, tokenB) = dataGenerator.generateUserWithAccessToken()

        val eventId = requireNotNull(dataGenerator.generateEvent(title = "EV").id)

        val created = mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(tokenA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(eventId, "mine", listOf("t1")))
        ).andExpect(status().isOk).andReturn()

        val memoId = objectMapper.readTree(created.response.contentAsString)["id"].asLong()

        val before = memoRepository.findById(memoId).orElseThrow()

        mockMvc.perform(
            patch("/api/v1/memos/$memoId")
                .header("Authorization", bearer(tokenB))
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(mapOf("content" to "hacked")))
        )
            .andExpect(status().isNotFound)

        val after = memoRepository.findById(memoId).orElseThrow()
        assertEquals(before.content, after.content)
        assertEquals(before.tags.map { it.tagId }.toSet(), after.tags.map { it.tagId }.toSet())
    }

    @Test
    fun `PATCH memos 메모 수정 토큰 없으면 401`() {
        mockMvc.perform(
            patch("/api/v1/memos/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(mapOf("content" to "x")))
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `PATCH memos tagNames에 중복 입력이 있어도 태그는 중복 연결되지 않는다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val userId = requireNotNull(user.id)

        val eventId = requireNotNull(
            dataGenerator.generateEvent(title = "EV").id
        )

        // 최초 생성: tag=[shared]
        val created = mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    toJson(
                        mapOf(
                            "eventId" to eventId,
                            "content" to "memo",
                            "tagNames" to listOf("shared")
                        )
                    )
                )
        ).andReturn()

        val memoId = objectMapper.readTree(created.response.contentAsString)["id"].asLong()

        // PATCH: 중복 포함
        mockMvc.perform(
            patch("/api/v1/memos/$memoId")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    toJson(
                        mapOf(
                            "tagNames" to listOf("shared", "shared", "new")
                        )
                    )
                )
        )
            .andExpect(status().isOk)

        val memo = memoRepository.findById(memoId).orElseThrow()

        val shared = tagRepository.findByUserIdAndName(userId, "shared").orElseThrow()
        val newTag = tagRepository.findByUserIdAndName(userId, "new").orElseThrow()

        // 정확히 2개만 연결돼야 함
        assertEquals(
            setOf(requireNotNull(shared.id), requireNotNull(newTag.id)),
            memo.tags.map { it.tagId }.toSet()
        )
    }

    // =========================================================
    // DELETE /api/v1/memos/{memoId}  메모 삭제
    // =========================================================

    @Test
    fun `DELETE memos 메모 삭제하면 204이고 DB에서 삭제된다`() {
        val (_, token) = dataGenerator.generateUserWithAccessToken()

        val eventId = requireNotNull(dataGenerator.generateEvent(title = "EV").id)

        val created = mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(eventId, "to-delete", listOf("t1", "t2")))
        ).andExpect(status().isOk).andReturn()

        val memoId = objectMapper.readTree(created.response.contentAsString)["id"].asLong()

        mockMvc.perform(
            delete("/api/v1/memos/$memoId")
                .header("Authorization", bearer(token))
        )
            .andExpect(status().isNoContent)

        assertTrue(memoRepository.findById(memoId).isEmpty)
        // memo_tags는 FK cascade로 같이 지워지는 스키마(또는 JDBC aggregate 삭제)라 별도 검증 생략
    }

    @Test
    fun `DELETE memos 메모 삭제 남의 메모면 404이고 DB는 그대로다`() {
        val (_, tokenA) = dataGenerator.generateUserWithAccessToken()
        val (_, tokenB) = dataGenerator.generateUserWithAccessToken()

        val eventId = requireNotNull(dataGenerator.generateEvent(title = "EV").id)

        val created = mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(tokenA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(eventId, "mine", listOf("t1")))
        ).andExpect(status().isOk).andReturn()

        val memoId = objectMapper.readTree(created.response.contentAsString)["id"].asLong()

        mockMvc.perform(
            delete("/api/v1/memos/$memoId")
                .header("Authorization", bearer(tokenB))
        )
            .andExpect(status().isNotFound)

        assertTrue(memoRepository.findById(memoId).isPresent)
    }

    @Test
    fun `DELETE memos 메모 삭제해도 해당 메모가 가진 tag는 삭제되지 않는다 고아 tag도 마찬가지`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val userId = requireNotNull(user.id)

        val e1 = requireNotNull(dataGenerator.generateEvent(title = "EV1").id)

        val created = mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(e1, "m1", listOf("shared", "orphan")))
        ).andExpect(status().isOk).andReturn()

        val memoId = objectMapper.readTree(created.response.contentAsString)["id"].asLong()

        val sharedTagId = requireNotNull(tagRepository.findByUserIdAndName(userId, "shared").orElseThrow().id)
        val orphanTagId = requireNotNull(tagRepository.findByUserIdAndName(userId, "orphan").orElseThrow().id)

        // delete memo
        mockMvc.perform(
            delete("/api/v1/memos/$memoId")
                .header("Authorization", bearer(token))
        )
            .andExpect(status().isNoContent)

        assertTrue(memoRepository.findById(memoId).isEmpty)

        // ✅ tags는 절대 삭제되지 않음 (고아여도)
        assertTrue(tagRepository.findById(sharedTagId).isPresent)
        assertTrue(tagRepository.findById(orphanTagId).isPresent)
    }

    @Test
    fun `DELETE memos 메모 삭제 토큰 없으면 401`() {
        mockMvc.perform(delete("/api/v1/memos/1"))
            .andExpect(status().isUnauthorized)
    }

    // =========================================================
    // Tag로 Memo 조회 동작 테스트 (새로 추가)
    // - 메모 생성 시 기존 태그면 리스트에 뜬다
    // - 메모 수정으로 새 태그 추가하면 리스트에 뜬다
    // - 메모 수정 시 기존 태그를 그대로 포함해도 리스트에 계속 뜬다
    // - 메모 삭제하면 리스트에서 빠진다(조인 row가 삭제되니까)
    // =========================================================

    @Test
    fun `GET memos by tag 메모 생성 시 사용한 태그가 기존 태그라면 해당 태그 memo 리스트에 뜬다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val userId = requireNotNull(user.id)

        val e1 = requireNotNull(dataGenerator.generateEvent(title = "EV1").id)
        val e2 = requireNotNull(dataGenerator.generateEvent(title = "EV2").id)

        val created1 = mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(e1, "m1", listOf("same")))
        ).andExpect(status().isOk).andReturn()
        val memoId1 = objectMapper.readTree(created1.response.contentAsString)["id"].asLong()

        val sameTagId = requireNotNull(tagRepository.findByUserIdAndName(userId, "same").orElseThrow().id)

        val created2 = mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(e2, "m2", listOf("same")))
        ).andExpect(status().isOk).andReturn()
        val memoId2 = objectMapper.readTree(created2.response.contentAsString)["id"].asLong()

        val res = listMemosByTag(token, sameTagId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.memos").isArray)
            .andReturn()

        val root = objectMapper.readTree(res.response.contentAsString)
        val ids = root["memos"].map { it["id"].asLong() }.toSet()

        assertTrue(ids.contains(memoId1))
        assertTrue(ids.contains(memoId2))
    }

    @Test
    fun `GET memos by tag 메모 수정으로 새 태그를 추가하면 해당 태그 memo 리스트에 뜬다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val userId = requireNotNull(user.id)

        val e1 = requireNotNull(dataGenerator.generateEvent(title = "EV1").id)

        // 처음엔 태그 없음
        val created = mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(e1, "m1"))
        ).andExpect(status().isOk).andReturn()

        val memoId = objectMapper.readTree(created.response.contentAsString)["id"].asLong()

        // PATCH로 tagNames 설정
        mockMvc.perform(
            patch("/api/v1/memos/$memoId")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(mapOf("tagNames" to listOf("added"))))
        )
            .andExpect(status().isOk)

        val addedTagId = requireNotNull(tagRepository.findByUserIdAndName(userId, "added").orElseThrow().id)

        val res = listMemosByTag(token, addedTagId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.memos").isArray)
            .andReturn()

        val root = objectMapper.readTree(res.response.contentAsString)
        val ids = root["memos"].map { it["id"].asLong() }.toSet()

        assertTrue(ids.contains(memoId))
    }

    @Test
    fun `GET memos by tag 메모 수정 시 기존 태그와 동일한 태그를 포함하면 해당 태그 memo 리스트에 계속 뜬다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val userId = requireNotNull(user.id)

        val e1 = requireNotNull(dataGenerator.generateEvent(title = "EV1").id)

        // memo 생성: tags=[shared]
        val created = mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(e1, "m1", listOf("shared")))
        ).andExpect(status().isOk).andReturn()

        val memoId = objectMapper.readTree(created.response.contentAsString)["id"].asLong()
        val sharedTagId = requireNotNull(tagRepository.findByUserIdAndName(userId, "shared").orElseThrow().id)

        // PATCH: tags=[shared, other] (shared 유지)
        mockMvc.perform(
            patch("/api/v1/memos/$memoId")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(mapOf("tagNames" to listOf("shared", "other"))))
        )
            .andExpect(status().isOk)

        val res = listMemosByTag(token, sharedTagId)
            .andExpect(status().isOk)
            .andReturn()

        val root = objectMapper.readTree(res.response.contentAsString)
        val ids = root["memos"].map { it["id"].asLong() }.toSet()

        assertTrue(ids.contains(memoId))
    }

    @Test
    fun `GET memos by tag 메모를 삭제하면 해당 태그 memo 리스트에서 사라진다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val userId = requireNotNull(user.id)

        val e1 = requireNotNull(dataGenerator.generateEvent(title = "EV1").id)

        val created = mockMvc.perform(
            post("/api/v1/memos")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(e1, "m1", listOf("t")))
        ).andExpect(status().isOk).andReturn()

        val memoId = objectMapper.readTree(created.response.contentAsString)["id"].asLong()
        val tagId = requireNotNull(tagRepository.findByUserIdAndName(userId, "t").orElseThrow().id)

        // 삭제 전: 리스트에 포함
        run {
            val res = listMemosByTag(token, tagId)
                .andExpect(status().isOk)
                .andReturn()
            val ids = objectMapper.readTree(res.response.contentAsString)["memos"].map { it["id"].asLong() }.toSet()
            assertTrue(ids.contains(memoId))
        }

        // delete memo
        mockMvc.perform(
            delete("/api/v1/memos/$memoId")
                .header("Authorization", bearer(token))
        ).andExpect(status().isNoContent)

        // 삭제 후: 리스트에서 제거
        run {
            val res = listMemosByTag(token, tagId)
                .andExpect(status().isOk)
                .andReturn()
            val ids = objectMapper.readTree(res.response.contentAsString)["memos"].map { it["id"].asLong() }.toSet()
            assertFalse(ids.contains(memoId))
        }

        // tag는 남아야 함
        assertTrue(tagRepository.findById(tagId).isPresent)
    }
}
