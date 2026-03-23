package com.team1.hangsha

import com.team1.hangsha.category.repository.CategoryGroupRepository
import com.team1.hangsha.category.repository.CategoryRepository
import com.team1.hangsha.event.repository.EventRepository
import com.team1.hangsha.helper.IntegrationTestBase
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.LocalDate

class EventIntegrationTest : IntegrationTestBase() {

    @Autowired lateinit var categoryGroupRepository: CategoryGroupRepository
    @Autowired lateinit var categoryRepository: CategoryRepository
    @Autowired lateinit var eventRepository: EventRepository

    private fun ymd(d: LocalDate) = d.toString()

    private fun monthUrl(
        from: LocalDate,
        to: LocalDate,
        statusIds: List<Long>? = null,
        eventTypeIds: List<Long>? = null,
        orgIds: List<Long>? = null,
    ): String {
        fun join(name: String, ids: List<Long>?) =
            ids?.joinToString("&") { "$name=$it" }?.let { "&$it" } ?: ""

        return buildString {
            append("/api/v1/events/month?from=${ymd(from)}&to=${ymd(to)}")
            append(join("statusId", statusIds))
            append(join("eventTypeId", eventTypeIds))
            append(join("orgId", orgIds))
        }
    }

    // seed_categories.sql 기반: 실제 존재하는 카테고리 id 조회
    private fun seedCategoryId(groupName: String, categoryName: String): Long {
        val group = categoryGroupRepository.findByName(groupName)
            ?: error("seed group not found: $groupName")
        val cat = categoryRepository.findByGroupIdAndName(requireNotNull(group.id), categoryName)
            ?: error("seed category not found: group=$groupName name=$categoryName")
        return requireNotNull(cat.id)
    }

    // =========================================================
    // 이벤트 Public API 기본 동작 테스트 (비로그인)
    // =========================================================

    @Test
    fun `이벤트 월 조회 from 이 to 보다 이후면 400`() {
        val from = LocalDate.now().plusDays(10)
        val to = LocalDate.now()

        mockMvc.perform(get(monthUrl(from, to)))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `이벤트 월 조회 날짜별 bucket 이 생성되고 overlap 이 반영된다`() {
        val today = LocalDate.now()
        val from = today
        val to = today.plusDays(6)

        dataGenerator.generateEvent(
            title = "E1",
            eventStart = from.atStartOfDay().plusHours(10),
            eventEnd = from.atStartOfDay().plusHours(12),
        )

        dataGenerator.generateEvent(
            title = "E2",
            eventStart = null,
            eventEnd = null,
            applyStart = from.atStartOfDay(),
            applyEnd = from.plusDays(2).atTime(23, 59, 59),
        )

        dataGenerator.generateEvent(
            title = "OUT",
            eventStart = to.plusDays(5).atStartOfDay(),
            eventEnd = to.plusDays(5).atStartOfDay().plusHours(1),
        )

        val res = mockMvc.perform(get(monthUrl(from, to)))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.range.from").value(ymd(from)))
            .andExpect(jsonPath("$.range.to").value(ymd(to)))
            .andExpect(jsonPath("$.byDate").isMap)
            .andReturn()

        val root = objectMapper.readTree(res.response.contentAsString)
        val byDate = root["byDate"]

        val d0 = ymd(from)
        require(!byDate[d0].isMissingNode) { "expected byDate[$d0] to exist" }
        require(byDate[d0]["events"].isArray) { "expected byDate[$d0].events array" }

        val d1 = ymd(from.plusDays(1))
        val d2 = ymd(from.plusDays(2))
        require(!byDate[d1].isMissingNode) { "expected byDate[$d1] to exist (E2 overlap)" }
        require(!byDate[d2].isMissingNode) { "expected byDate[$d2] to exist (E2 overlap)" }

        byDate.fields().forEach { (_, bucket) ->
            val titles = bucket["events"].mapNotNull { it["title"]?.asText() }
            require(!titles.contains("OUT")) { "unexpected OUT in month events" }
        }
    }

    @Test
    fun `이벤트 월 조회 statusId eventTypeId orgId 필터가 적용된다`() {
        val today = LocalDate.now()
        val from = today
        val to = today.plusDays(3)

        val statusRecruiting = seedCategoryId("모집현황", "모집중")
        val statusClosed = seedCategoryId("모집현황", "모집마감")
        val typeEdu = seedCategoryId("프로그램 유형", "교육(특강/세미나)")
        val typeEtc = seedCategoryId("프로그램 유형", "기타")

        val orgA = dataGenerator.generateOrgCategory(name = "ORG-A")
        val orgB = dataGenerator.generateOrgCategory(name = "ORG-B")

        dataGenerator.generateEvent(
            title = "MATCH",
            orgId = orgA.id!!,
            statusId = statusRecruiting,
            eventTypeId = typeEdu,
            applyStart = from.atStartOfDay(),
            applyEnd = to.atTime(23, 59, 59),
            eventStart = null,
            eventEnd = null,
        )

        dataGenerator.generateEvent(
            title = "NO STATUS",
            orgId = orgA.id!!,
            statusId = statusClosed,
            eventTypeId = typeEdu,
            applyStart = from.atStartOfDay(),
            applyEnd = to.atTime(23, 59, 59),
            eventStart = null,
            eventEnd = null,
        )

        dataGenerator.generateEvent(
            title = "NO ORG",
            orgId = orgB.id!!,
            statusId = statusRecruiting,
            eventTypeId = typeEdu,
            applyStart = from.atStartOfDay(),
            applyEnd = to.atTime(23, 59, 59),
            eventStart = null,
            eventEnd = null,
        )

        dataGenerator.generateEvent(
            title = "NO TYPE",
            orgId = orgA.id!!,
            statusId = statusRecruiting,
            eventTypeId = typeEtc,
            applyStart = from.atStartOfDay(),
            applyEnd = to.atTime(23, 59, 59),
            eventStart = null,
            eventEnd = null,
        )

        val url = monthUrl(
            from = from,
            to = to,
            statusIds = listOf(statusRecruiting),
            eventTypeIds = listOf(typeEdu),
            orgIds = listOf(orgA.id!!),
        )

        val res = mockMvc.perform(get(url))
            .andExpect(status().isOk)
            .andReturn()

        val root = objectMapper.readTree(res.response.contentAsString)
        val byDate = root["byDate"]

        val allTitles = mutableListOf<String>()
        byDate.fields().forEach { (_, bucket) ->
            bucket["events"].forEach { ev -> allTitles += ev["title"].asText() }
        }

        require(allTitles.contains("MATCH")) { "expected MATCH in results" }
        require(!allTitles.contains("NO STATUS")) { "unexpected NO STATUS" }
        require(!allTitles.contains("NO ORG")) { "unexpected NO ORG" }
        require(!allTitles.contains("NO TYPE")) { "unexpected NO TYPE" }
    }

    @Test
    fun `이벤트 월 조회는 event 기간과 apply 기간을 모두 기준으로 bucket 에 포함한다`() {
        val from = LocalDate.now()
        val to = from.plusDays(4)

        dataGenerator.generateEvent(
            title = "BOTH RANGE",
            applyStart = from.atStartOfDay(),
            applyEnd = from.plusDays(1).atTime(23, 59, 59),
            eventStart = from.plusDays(3).atTime(9, 0),
            eventEnd = from.plusDays(4).atTime(18, 0),
        )

        val res = mockMvc.perform(get(monthUrl(from, to)))
            .andExpect(status().isOk)
            .andReturn()

        val byDate = objectMapper.readTree(res.response.contentAsString)["byDate"]
        val expectedDates = listOf(from, from.plusDays(1), from.plusDays(3), from.plusDays(4)).map(::ymd)

        expectedDates.forEach { day ->
            val titles = byDate[day]["events"].map { it["title"].asText() }
            require(titles.count { it == "BOTH RANGE" } == 1) {
                "expected BOTH RANGE exactly once in $day, but got $titles"
            }
        }

        require(byDate[ymd(from.plusDays(2))].isMissingNode) {
            "expected no bucket on ${ymd(from.plusDays(2))}"
        }
    }

    @Test
    fun `이벤트 일 조회는 event 기간과 apply 기간 중 하나라도 겹치면 포함한다`() {
        val date = LocalDate.now()

        dataGenerator.generateEvent(
            title = "APPLY ONLY TODAY",
            applyStart = date.minusDays(1).atStartOfDay(),
            applyEnd = date.atTime(23, 59, 59),
            eventStart = date.plusDays(5).atTime(9, 0),
            eventEnd = date.plusDays(5).atTime(11, 0),
        )

        dataGenerator.generateEvent(
            title = "OTHER",
            applyStart = date.plusDays(2).atStartOfDay(),
            applyEnd = date.plusDays(2).atTime(23, 59, 59),
            eventStart = date.plusDays(3).atTime(9, 0),
            eventEnd = date.plusDays(3).atTime(11, 0),
        )

        val res = mockMvc.perform(get("/api/v1/events/day?date=${ymd(date)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andReturn()

        val titles = objectMapper.readTree(res.response.contentAsString)["items"].map { it["title"].asText() }
        assertEquals(listOf("APPLY ONLY TODAY"), titles)
    }

    @Test
    fun `이벤트 일 조회 기본 page size total items 동작하고 정렬 desc 이다`() {
        val date = LocalDate.now()
        val dayStart = date.atStartOfDay()

        dataGenerator.generateEvent(title = "D1", eventStart = dayStart.plusHours(9), eventEnd = dayStart.plusHours(10))
        dataGenerator.generateEvent(title = "D2", eventStart = dayStart.plusHours(11), eventEnd = dayStart.plusHours(12))
        dataGenerator.generateEvent(title = "D3", eventStart = dayStart.plusHours(13), eventEnd = dayStart.plusHours(14))

        dataGenerator.generateEvent(
            title = "OTHER",
            eventStart = date.plusDays(2).atStartOfDay(),
            eventEnd = date.plusDays(2).atStartOfDay().plusHours(1),
        )

        val res = mockMvc.perform(get("/api/v1/events/day?date=${ymd(date)}"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.date").value(ymd(date)))
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.items.length()").value(3))
            .andReturn()

        val root = objectMapper.readTree(res.response.contentAsString)
        val titles = root["items"].map { it["title"].asText() }
        assertEquals(listOf("D3", "D2", "D1"), titles)
    }

    @Test
    fun `이벤트 일 조회 page size 페이징이 동작한다`() {
        val date = LocalDate.now()
        val dayStart = date.atStartOfDay()

        repeat(5) { idx ->
            dataGenerator.generateEvent(
                title = "P${idx + 1}",
                eventStart = dayStart.plusHours((9 + idx).toLong()),
                eventEnd = dayStart.plusHours((10 + idx).toLong()),
            )
        }

        mockMvc.perform(get("/api/v1/events/day?date=${ymd(date)}&page=1&size=2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.total").value(5))
            .andExpect(jsonPath("$.items.length()").value(2))

        mockMvc.perform(get("/api/v1/events/day?date=${ymd(date)}&page=3&size=2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
    }

    @Test
    fun `이벤트 제목 검색 query 가 공백이면 400`() {
        mockMvc.perform(get("/api/v1/events/search/title?query=   "))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `이벤트 제목 검색 like 검색과 페이징이 동작한다`() {
        dataGenerator.generateEvent(title = "Alpha 1")
        dataGenerator.generateEvent(title = "Alpha 2")
        dataGenerator.generateEvent(title = "Alpha 3")
        dataGenerator.generateEvent(title = "Beta 1")

        mockMvc.perform(get("/api/v1/events/search/title?query=Alpha&page=1&size=2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.items.length()").value(2))

        mockMvc.perform(get("/api/v1/events/search/title?query=Alpha&page=2&size=2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
    }

    @Test
    fun `이벤트 상세 조회 없는 이벤트면 404`() {
        mockMvc.perform(get("/api/v1/events/999999999"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `이벤트 상세 조회 비로그인인 경우 personalization 필드는 null 이다`() {
        val event = dataGenerator.generateEvent(title = "DETAIL")

        mockMvc.perform(get("/api/v1/events/${event.id}"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").value(event.id!!))
            .andExpect(jsonPath("$.title").value("DETAIL"))
            .andExpect(jsonPath("$.isBookmarked").value(nullValue()))
            .andExpect(jsonPath("$.isInterested").value(nullValue()))
            .andExpect(jsonPath("$.matchedInterestPriority").value(nullValue()))
    }

    // =========================================================
    // 북마크 기능 테스트
    // =========================================================

    @Test
    fun `북마크 이벤트 상세 조회 로그인 상태에서 북마크 전후 isBookmarked 가 false true 로 바뀐다`() {
        val (_, token) = dataGenerator.generateUserWithAccessToken()
        val event = dataGenerator.generateEvent(title = "BM DETAIL")

        mockMvc.perform(
            get("/api/v1/events/${event.id}")
                .header("Authorization", bearer(token))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isBookmarked").value(false))

        mockMvc.perform(
            post("/api/v1/events/${event.id}/bookmark")
                .header("Authorization", bearer(token))
        )
            .andExpect(status().isNoContent)

        mockMvc.perform(
            get("/api/v1/events/${event.id}")
                .header("Authorization", bearer(token))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isBookmarked").value(true))
    }

    @Test
    fun `북마크 이벤트 일 조회 로그인 상태에서 북마크된 이벤트는 isBookmarked true 로 내려온다`() {
        val (_, token) = dataGenerator.generateUserWithAccessToken()
        val date = LocalDate.now()
        val dayStart = date.atStartOfDay()

        val e1 = dataGenerator.generateEvent(title = "B1", eventStart = dayStart.plusHours(9), eventEnd = dayStart.plusHours(10))
        val e2 = dataGenerator.generateEvent(title = "B2", eventStart = dayStart.plusHours(11), eventEnd = dayStart.plusHours(12))

        mockMvc.perform(
            post("/api/v1/events/${e2.id}/bookmark")
                .header("Authorization", bearer(token))
        ).andExpect(status().isNoContent)

        val res = mockMvc.perform(
            get("/api/v1/events/day?date=${ymd(date)}&page=1&size=20")
                .header("Authorization", bearer(token))
        )
            .andExpect(status().isOk)
            .andReturn()

        val items = objectMapper.readTree(res.response.contentAsString)["items"]
        val map = items.associate {
            it["title"].asText() to it["isBookmarked"].asBoolean()
        }

        require(map["B2"] == true) { "expected B2 isBookmarked=true" }
        require(map["B1"] == false) { "expected B1 isBookmarked=false" }

        requireNotNull(e1.id); requireNotNull(e2.id)
    }

    @Test
    fun `북마크 목록 조회 북마크 후 목록에 나오고 삭제 후 사라진다 그리고 목록의 isBookmarked 는 항상 true 다`() {
        val (_, token) = dataGenerator.generateUserWithAccessToken()

        val e1 = dataGenerator.generateEvent(title = "M1")
        val e2 = dataGenerator.generateEvent(title = "M2")

        mockMvc.perform(
            post("/api/v1/events/${e1.id}/bookmark")
                .header("Authorization", bearer(token))
        ).andExpect(status().isNoContent)

        mockMvc.perform(
            post("/api/v1/events/${e2.id}/bookmark")
                .header("Authorization", bearer(token))
        ).andExpect(status().isNoContent)

        mockMvc.perform(
            get("/api/v1/users/me/bookmarks?page=1&size=20")
                .header("Authorization", bearer(token))
        )
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.items").isArray)
            .andExpect(jsonPath("$.items[0].isBookmarked").value(true))

        mockMvc.perform(
            delete("/api/v1/events/${e1.id}/bookmark")
                .header("Authorization", bearer(token))
        ).andExpect(status().isNoContent)

        mockMvc.perform(
            get("/api/v1/users/me/bookmarks?page=1&size=20")
                .header("Authorization", bearer(token))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(1))
    }

    @Test
    fun `북마크 API 없는 이벤트면 404`() {
        val (_, token) = dataGenerator.generateUserWithAccessToken()

        mockMvc.perform(
            post("/api/v1/events/999999999/bookmark")
                .header("Authorization", bearer(token))
        ).andExpect(status().isNotFound)

        mockMvc.perform(
            delete("/api/v1/events/999999999/bookmark")
                .header("Authorization", bearer(token))
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `북마크 API 인증이 없으면 401`() {
        val e = dataGenerator.generateEvent()

        mockMvc.perform(post("/api/v1/events/${e.id}/bookmark"))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(delete("/api/v1/events/${e.id}/bookmark"))
            .andExpect(status().isUnauthorized)

        mockMvc.perform(get("/api/v1/users/me/bookmarks"))
            .andExpect(status().isUnauthorized)
    }

    // =========================================================
    // Personalization excluded keyword
    // =========================================================

    @Test
    fun `개인화 excluded keyword 가 있으면 로그인 상태에서 day search month 에서 제외된다`() {
        val (_, token) = dataGenerator.generateUserWithAccessToken()
        val date = LocalDate.now()
        val dayStart = date.atStartOfDay()

        mockMvc.perform(
            post("/api/v1/users/me/excluded-keywords")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(mapOf("keyword" to "apple")))
        ).andExpect(status().isCreated)

        dataGenerator.generateEvent(
            title = "apple conference",
            eventStart = dayStart.plusHours(9),
            eventEnd = dayStart.plusHours(10),
        )
        dataGenerator.generateEvent(
            title = "banana conference",
            eventStart = dayStart.plusHours(11),
            eventEnd = dayStart.plusHours(12),
        )

        val dayRes = mockMvc.perform(
            get("/api/v1/events/day?date=${ymd(date)}&page=1&size=20")
                .header("Authorization", bearer(token))
        )
            .andExpect(status().isOk)
            .andReturn()

        val dayTitles = objectMapper.readTree(dayRes.response.contentAsString)["items"]
            .map { it["title"].asText() }
        require(dayTitles.contains("banana conference")) { "expected banana in day results" }
        require(!dayTitles.contains("apple conference")) { "apple should be excluded in day results" }

        val searchRes = mockMvc.perform(
            get("/api/v1/events/search/title?query=conference&page=1&size=20")
                .header("Authorization", bearer(token))
        )
            .andExpect(status().isOk)
            .andReturn()

        val searchTitles = objectMapper.readTree(searchRes.response.contentAsString)["items"]
            .map { it["title"].asText() }
        require(searchTitles.contains("banana conference")) { "expected banana in search results" }
        require(!searchTitles.contains("apple conference")) { "apple should be excluded in search results" }

        val from = date
        val to = date.plusDays(2)
        val monthRes = mockMvc.perform(
            get(monthUrl(from, to))
                .header("Authorization", bearer(token))
        )
            .andExpect(status().isOk)
            .andReturn()

        val byDate = objectMapper.readTree(monthRes.response.contentAsString)["byDate"]
        byDate.fields().forEach { (_, bucket) ->
            val titles = bucket["events"].mapNotNull { it["title"]?.asText() }
            require(!titles.contains("apple conference")) { "apple should be excluded in month results" }
        }
    }

    // =========================================================
    // Personalization interest category
    // =========================================================

    @Test
    fun `개인화 interest category 매칭이면 detail 과 day 에서 isInterested 와 matchedInterestPriority 가 채워진다`() {
        val (u, token) = dataGenerator.generateUserWithAccessToken()

        val statusRecruiting = seedCategoryId("모집현황", "모집중")
        val typeEdu = seedCategoryId("프로그램 유형", "교육(특강/세미나)")
        val org = dataGenerator.generateOrgCategory(name = "ORG I")

        dataGenerator.addUserInterestCategory(u, org, priority = 1)

        val date = LocalDate.now()
        val dayStart = date.atStartOfDay()

        val e = dataGenerator.generateEvent(
            title = "INTEREST",
            orgId = org.id!!,
            statusId = statusRecruiting,
            eventTypeId = typeEdu,
            eventStart = dayStart.plusHours(9),
            eventEnd = dayStart.plusHours(10),
        )

        mockMvc.perform(
            get("/api/v1/events/${e.id}")
                .header("Authorization", bearer(token))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isInterested").value(true))
            .andExpect(jsonPath("$.matchedInterestPriority").value(1))

        val dayRes = mockMvc.perform(
            get("/api/v1/events/day?date=${ymd(date)}&page=1&size=20")
                .header("Authorization", bearer(token))
        )
            .andExpect(status().isOk)
            .andReturn()

        val items = objectMapper.readTree(dayRes.response.contentAsString)["items"]
        val target = items.firstOrNull { it["id"].asLong() == e.id!! }
            ?: error("INTEREST event not found in day result")

        require(target["isInterested"].asBoolean()) { "expected isInterested=true" }
        require(target["matchedInterestPriority"].asInt() == 1) { "expected matchedInterestPriority=1" }
    }

    @Test
    fun `개인화 day 에서 interest 우선정렬 되고 excluded keyword 도 같이 적용된다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val date = LocalDate.now()
        val dayStart = date.atStartOfDay()

        val orgP1 = dataGenerator.generateOrgCategory(name = "ORG P1")
        val orgP2 = dataGenerator.generateOrgCategory(name = "ORG P2")
        dataGenerator.addUserInterestCategory(user, orgP1, priority = 1)
        dataGenerator.addUserInterestCategory(user, orgP2, priority = 2)

        mockMvc.perform(
            post("/api/v1/users/me/excluded-keywords")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(mapOf("keyword" to "apple")))
        ).andExpect(status().isCreated)

        dataGenerator.generateEvent(
            title = "apple P1 should be hidden",
            orgId = orgP1.id!!,
            eventStart = dayStart.plusHours(9),
            eventEnd = dayStart.plusHours(10),
        )
        dataGenerator.generateEvent(
            title = "P1 visible",
            orgId = orgP1.id!!,
            eventStart = dayStart.plusHours(11),
            eventEnd = dayStart.plusHours(12),
        )
        dataGenerator.generateEvent(
            title = "P2 visible",
            orgId = orgP2.id!!,
            eventStart = dayStart.plusHours(13),
            eventEnd = dayStart.plusHours(14),
        )
        dataGenerator.generateEvent(
            title = "NON visible",
            orgId = null,
            eventStart = dayStart.plusHours(15),
            eventEnd = dayStart.plusHours(16),
        )

        val res = mockMvc.perform(
            get("/api/v1/events/day?date=${date}&page=1&size=20")
                .header("Authorization", bearer(token))
        )
            .andExpect(status().isOk)
            .andReturn()

        val items = objectMapper.readTree(res.response.contentAsString)["items"]
        val titles = items.map { it["title"].asText() }

        require(!titles.contains("apple P1 should be hidden")) { "excluded keyword event leaked" }
        assertEquals(listOf("P1 visible", "P2 visible", "NON visible"), titles)

        val first = items[0]
        require(first["isInterested"].asBoolean()) { "expected isInterested=true" }
        require(first["matchedInterestPriority"].asInt() == 1) { "expected priority=1" }
    }

    @Test
    fun `개인화 search 에서 interest 우선정렬 되고 excluded keyword 도 같이 적용된다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val orgP1 = dataGenerator.generateOrgCategory(name = "ORG P1")
        val orgP2 = dataGenerator.generateOrgCategory(name = "ORG P2")
        dataGenerator.addUserInterestCategory(user, orgP1, priority = 1)
        dataGenerator.addUserInterestCategory(user, orgP2, priority = 2)

        mockMvc.perform(
            post("/api/v1/users/me/excluded-keywords")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(mapOf("keyword" to "apple")))
        ).andExpect(status().isCreated)

        dataGenerator.generateEvent(title = "apple visible P1 hidden", orgId = orgP1.id!!)
        dataGenerator.generateEvent(title = "visible P1", orgId = orgP1.id!!)
        dataGenerator.generateEvent(title = "visible P2", orgId = orgP2.id!!)
        dataGenerator.generateEvent(title = "visible NON")

        val res = mockMvc.perform(
            get("/api/v1/events/search/title?query=visible&page=1&size=20")
                .header("Authorization", bearer(token))
        )
            .andExpect(status().isOk)
            .andReturn()

        val items = objectMapper.readTree(res.response.contentAsString)["items"]
        val titles = items.map { it["title"].asText() }

        require(!titles.contains("apple visible P1 hidden")) { "excluded keyword event leaked" }
        assertEquals(listOf("visible P1", "visible P2", "visible NON"), titles)
    }

    @Test
    fun `개인화 month 의 날짜 bucket 내부에서도 interest 우선정렬 되고 excluded keyword 는 숨긴다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val from = LocalDate.now()
        val to = from.plusDays(2)

        val orgP1 = dataGenerator.generateOrgCategory(name = "ORG P1")
        val orgP2 = dataGenerator.generateOrgCategory(name = "ORG P2")
        dataGenerator.addUserInterestCategory(user, orgP1, priority = 1)
        dataGenerator.addUserInterestCategory(user, orgP2, priority = 2)

        mockMvc.perform(
            post("/api/v1/users/me/excluded-keywords")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(mapOf("keyword" to "apple")))
        ).andExpect(status().isCreated)

        val dayStart = from.atStartOfDay()
        dataGenerator.generateEvent(title = "NON", eventStart = dayStart.plusHours(9), eventEnd = dayStart.plusHours(10))
        dataGenerator.generateEvent(title = "P2", orgId = orgP2.id!!, eventStart = dayStart.plusHours(11), eventEnd = dayStart.plusHours(12))
        dataGenerator.generateEvent(title = "apple P1 hidden", orgId = orgP1.id!!, eventStart = dayStart.plusHours(13), eventEnd = dayStart.plusHours(14))
        dataGenerator.generateEvent(title = "P1", orgId = orgP1.id!!, eventStart = dayStart.plusHours(15), eventEnd = dayStart.plusHours(16))

        val res = mockMvc.perform(
            get("/api/v1/events/month?from=$from&to=$to")
                .header("Authorization", bearer(token))
        )
            .andExpect(status().isOk)
            .andReturn()

        val byDate = objectMapper.readTree(res.response.contentAsString)["byDate"]
        val bucket = byDate[from.toString()]
        require(bucket != null && !bucket.isMissingNode) { "expected bucket for $from" }

        val titles = bucket["events"].map { it["title"].asText() }
        require(!titles.contains("apple P1 hidden")) { "excluded keyword event leaked" }
        assertEquals(listOf("P1", "P2", "NON"), titles)
    }

    @Test
    fun `개인화 day 에서 같은 관심 카테고리 이벤트 두 개가 둘 다 상단으로 올라온다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()

        val date = LocalDate.now()
        val dayStart = date.atStartOfDay()

        val orgP1 = dataGenerator.generateOrgCategory(name = "ORG P1")
        val orgP2 = dataGenerator.generateOrgCategory(name = "ORG P2")

        dataGenerator.addUserInterestCategory(user, orgP1, priority = 1)
        dataGenerator.addUserInterestCategory(user, orgP2, priority = 2)

        val p1a = dataGenerator.generateEvent(
            title = "P1 A",
            orgId = orgP1.id!!,
            eventStart = dayStart.plusHours(9),
            eventEnd = dayStart.plusHours(10),
        )
        val p1b = dataGenerator.generateEvent(
            title = "P1 B",
            orgId = orgP1.id!!,
            eventStart = dayStart.plusHours(11),
            eventEnd = dayStart.plusHours(12),
        )

        dataGenerator.generateEvent(
            title = "P2",
            orgId = orgP2.id!!,
            eventStart = dayStart.plusHours(13),
            eventEnd = dayStart.plusHours(14),
        )

        dataGenerator.generateEvent(
            title = "NON 1",
            orgId = null,
            eventStart = dayStart.plusHours(15),
            eventEnd = dayStart.plusHours(16),
        )
        dataGenerator.generateEvent(
            title = "NON 2",
            orgId = null,
            eventStart = dayStart.plusHours(17),
            eventEnd = dayStart.plusHours(18),
        )

        val res = mockMvc.perform(
            get("/api/v1/events/day?date=${date}&page=1&size=20")
                .header("Authorization", bearer(token))
        )
            .andExpect(status().isOk)
            .andReturn()

        val items = objectMapper.readTree(res.response.contentAsString)["items"]
        val titles = items.map { it["title"].asText() }

        val top2 = titles.take(2).toSet()
        require(top2 == setOf("P1 A", "P1 B")) {
            "expected top2 to be P1 A and P1 B, but got top2=$top2 (all=$titles)"
        }

        require(titles.getOrNull(2) == "P2") {
            "expected 3rd to be P2, but got ${titles.getOrNull(2)} (all=$titles)"
        }

        val top2Priorities = items.take(2).map { it["matchedInterestPriority"].asInt() }.toSet()
        require(top2Priorities == setOf(1)) {
            "expected top2 matchedInterestPriority=1, but got $top2Priorities"
        }

        val thirdPriority = items[2]["matchedInterestPriority"].asInt()
        require(thirdPriority == 2) {
            "expected 3rd matchedInterestPriority=2, but got $thirdPriority"
        }

        requireNotNull(p1a.id); requireNotNull(p1b.id)
    }

    @Test
    fun `개인화 excluded keyword app 은 apple happy 같은 substring 을 오탐으로 필터하지 않는다`() {
        val (_, token) = dataGenerator.generateUserWithAccessToken()

        val date = LocalDate.now()
        val dayStart = date.atStartOfDay()

        mockMvc.perform(
            post("/api/v1/users/me/excluded-keywords")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(mapOf("keyword" to "app")))
        )
            .andExpect(status().isCreated)

        dataGenerator.generateEvent(
            title = "app 개발 세미나",
            eventStart = dayStart.plusHours(9),
            eventEnd = dayStart.plusHours(10),
        )
        dataGenerator.generateEvent(
            title = "APP 출시",
            eventStart = dayStart.plusHours(11),
            eventEnd = dayStart.plusHours(12),
        )
        dataGenerator.generateEvent(
            title = "Apple 행사",
            eventStart = dayStart.plusHours(13),
            eventEnd = dayStart.plusHours(14),
        )
        dataGenerator.generateEvent(
            title = "happy hour",
            eventStart = dayStart.plusHours(15),
            eventEnd = dayStart.plusHours(16),
        )

        val res = mockMvc.perform(
            get("/api/v1/events/day?date=$date&page=1&size=50")
                .header("Authorization", bearer(token))
        )
            .andExpect(status().isOk)
            .andReturn()

        val root = objectMapper.readTree(res.response.contentAsString)
        val titles = root["items"].map { it["title"].asText() }

        require(titles.contains("Apple 행사")) { "expected Apple 행사 visible, titles=$titles" }
        require(titles.contains("happy hour")) { "expected happy hour visible, titles=$titles" }

        require(!titles.contains("app 개발 세미나")) { "expected app 개발 세미나 filtered, titles=$titles" }
        require(!titles.contains("APP 출시")) { "expected APP 출시 filtered, titles=$titles" }
    }
}