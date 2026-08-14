package com.team1.hangsha

import com.team1.hangsha.helper.IntegrationTestBase
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

class CategoryIntegrationTest : IntegrationTestBase() {

    @Test
    fun `GET category-groups with-categories - 그룹 sortOrder 오름차순, 각 그룹의 categories도 sortOrder 오름차순`() {
        val g2 = dataGenerator.generateCategoryGroup(name = "g2", sortOrder = 20)
        val g1 = dataGenerator.generateCategoryGroup(name = "g1", sortOrder = 10)

        val c12 = dataGenerator.generateCategory(group = g1, name = "c12", sortOrder = 2)
        val c11 = dataGenerator.generateCategory(group = g1, name = "c11", sortOrder = 1)

        val c21 = dataGenerator.generateCategory(group = g2, name = "c21", sortOrder = 1)

        mockMvc.perform(
            get("/api/v1/category-groups/with-categories")
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.items").isArray)
            .andExpect(jsonPath("$.items.length()").value(2))

            // 그룹 sortOrder 오름차순: g1(10) -> g2(20)
            .andExpect(jsonPath("$.items[0].group.id").value(g1.id!!))
            .andExpect(jsonPath("$.items[0].group.name").value("g1"))
            .andExpect(jsonPath("$.items[0].group.sortOrder").value(10))

            .andExpect(jsonPath("$.items[1].group.id").value(g2.id!!))
            .andExpect(jsonPath("$.items[1].group.name").value("g2"))
            .andExpect(jsonPath("$.items[1].group.sortOrder").value(20))

            // g1의 categories도 sortOrder 오름차순: c11(1) -> c12(2)
            .andExpect(jsonPath("$.items[0].categories.length()").value(2))
            .andExpect(jsonPath("$.items[0].categories[0].id").value(c11.id!!))
            .andExpect(jsonPath("$.items[0].categories[0].groupId").value(g1.id!!))
            .andExpect(jsonPath("$.items[0].categories[0].name").value("c11"))
            .andExpect(jsonPath("$.items[0].categories[0].sortOrder").value(1))

            .andExpect(jsonPath("$.items[0].categories[1].id").value(c12.id!!))
            .andExpect(jsonPath("$.items[0].categories[1].groupId").value(g1.id!!))
            .andExpect(jsonPath("$.items[0].categories[1].name").value("c12"))
            .andExpect(jsonPath("$.items[0].categories[1].sortOrder").value(2))

            // g2 categories
            .andExpect(jsonPath("$.items[1].categories.length()").value(1))
            .andExpect(jsonPath("$.items[1].categories[0].id").value(c21.id!!))
            .andExpect(jsonPath("$.items[1].categories[0].groupId").value(g2.id!!))
            .andExpect(jsonPath("$.items[1].categories[0].name").value("c21"))
            .andExpect(jsonPath("$.items[1].categories[0].sortOrder").value(1))
    }

    @Test
    fun `GET categories orgs - 두 번 이상 사용된 주체기관만 sortOrder 오름차순으로 반환`() {
        val orgGroup = dataGenerator.generateCategoryGroup(name = "주체기관", sortOrder = 1)
        val c1 = dataGenerator.generateCategory(group = orgGroup, name = "기관-1", sortOrder = 1)
        val c2 = dataGenerator.generateCategory(group = orgGroup, name = "기관-2", sortOrder = 2)
        val c3 = dataGenerator.generateCategory(group = orgGroup, name = "기관-3", sortOrder = 3)
        dataGenerator.generateEvent(orgId = c1.id)
        dataGenerator.generateEvent(orgId = c1.id)
        dataGenerator.generateEvent(orgId = c2.id)
        dataGenerator.generateEvent(orgId = c2.id)
        dataGenerator.generateEvent(orgId = c3.id)

        // 다른 그룹/카테고리 섞어도 응답엔 나오면 안 됨
        val otherGroup = dataGenerator.generateCategoryGroup(name = "기타", sortOrder = 2)
        dataGenerator.generateCategory(group = otherGroup, name = "기타-1", sortOrder = 1)

        mockMvc.perform(
            get("/api/v1/categories/orgs")
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.items").isArray)
            .andExpect(jsonPath("$.items.length()").value(2))

            // 두 번 사용된 기관만 sortOrder 순서로 반환
            .andExpect(jsonPath("$.items[0].id").value(c1.id!!))
            .andExpect(jsonPath("$.items[0].groupId").value(orgGroup.id!!))
            .andExpect(jsonPath("$.items[0].name").value("기관-1"))
            .andExpect(jsonPath("$.items[0].sortOrder").value(1))

            .andExpect(jsonPath("$.items[1].id").value(c2.id!!))
            .andExpect(jsonPath("$.items[1].groupId").value(orgGroup.id!!))
            .andExpect(jsonPath("$.items[1].name").value("기관-2"))
            .andExpect(jsonPath("$.items[1].sortOrder").value(2))
    }

    @Test
    fun `GET categories orgs - 주체기관 그룹이 없으면 404`() {
        // 아무것도 안 만들면 "주체기관" 없음 -> 404 기대
        mockMvc.perform(
            get("/api/v1/categories/orgs")
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `Category API - 인증 없이도 접근 가능`() {
        dataGenerator.generateCategoryGroup(name = "g1", sortOrder = 1)

        mockMvc.perform(get("/api/v1/category-groups/with-categories"))
            .andExpect(status().isOk)

        // org는 데이터가 없으면 404가 맞고, 401이 아니어야 함
        mockMvc.perform(get("/api/v1/categories/orgs"))
            .andExpect(status().isNotFound)
    }
}
