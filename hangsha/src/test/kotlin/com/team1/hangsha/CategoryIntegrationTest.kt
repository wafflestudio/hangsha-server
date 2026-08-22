package com.team1.hangsha

import com.team1.hangsha.helper.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

class CategoryIntegrationTest : IntegrationTestBase() {
    @Test
    fun `event status와 event type 목록은 공개 API로 조회한다`() {
        mockMvc.perform(get("/api/v1/event-statuses"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.items[0].id").isNumber)
            .andExpect(jsonPath("$.items[0].name").isString)
            .andExpect(jsonPath("$.items[0].sortOrder").isNumber)

        mockMvc.perform(get("/api/v1/event-types"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].id").isNumber)
    }

    @Test
    fun `organizations는 두 번 이상 사용된 기관만 반환한다`() {
        val first = dataGenerator.generateOrgCategory(name = "기관-1")
        val second = dataGenerator.generateOrgCategory(name = "기관-2")
        val once = dataGenerator.generateOrgCategory(name = "기관-3")
        repeat(2) { dataGenerator.generateEvent(orgId = first.id) }
        repeat(2) { dataGenerator.generateEvent(orgId = second.id) }
        dataGenerator.generateEvent(orgId = once.id)

        mockMvc.perform(get("/api/v1/organizations"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].id").value(first.id!!))
            .andExpect(jsonPath("$.items[1].id").value(second.id!!))
    }
}
