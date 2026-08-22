package com.team1.hangsha

import com.team1.hangsha.helper.IntegrationTestBase
import com.team1.hangsha.user.model.InterestCategoryType
import com.team1.hangsha.user.repository.UserInterestDomainRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

class UserPreferenceIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userInterestDomainRepository: UserInterestDomainRepository

    private fun replaceAllBody(vararg items: Triple<InterestCategoryType, Long, Int>) = toJson(
        mapOf("items" to items.map { (type, id, priority) ->
            mapOf("categoryType" to type.name, "categoryId" to id, "priority" to priority)
        }),
    )

    @Test
    fun `interest categories는 하나의 전역 우선순위 목록으로 교체된다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val first = dataGenerator.generateOrgCategory("기관-1")
        val second = dataGenerator.generateOrgCategory("기관-2")

        mockMvc.perform(put("/api/v1/users/me/interest-categories")
            .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
            .content(replaceAllBody(
                Triple(InterestCategoryType.ORGANIZATION, second.id!!, 1),
                Triple(InterestCategoryType.ORGANIZATION, first.id!!, 2),
            )))
            .andExpect(status().isNoContent)

        val rows = userInterestDomainRepository.findAllByUserId(user.id!!)
        assertEquals(listOf(1, 2), rows.map { it.priority })
        assertEquals(listOf(second.id, first.id), rows.map { it.categoryId })

        mockMvc.perform(get("/api/v1/users/me/interest-categories").header("Authorization", bearer(token)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].categoryType").value("ORGANIZATION"))
            .andExpect(jsonPath("$.items[0].categoryId").value(second.id!!))
    }

    @Test
    fun `같은 도메인과 id를 중복 등록하면 거부한다`() {
        val (_, token) = dataGenerator.generateUserWithAccessToken()
        val org = dataGenerator.generateOrgCategory()
        mockMvc.perform(put("/api/v1/users/me/interest-categories")
            .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
            .content(replaceAllBody(
                Triple(InterestCategoryType.ORGANIZATION, org.id!!, 1),
                Triple(InterestCategoryType.ORGANIZATION, org.id!!, 2),
            )))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `interest category 삭제에는 도메인 타입이 필요하다`() {
        val (user, token) = dataGenerator.generateUserWithAccessToken()
        val org = dataGenerator.generateOrgCategory()
        userInterestDomainRepository.add(user.id!!, InterestCategoryType.ORGANIZATION, org.id!!, 1)

        mockMvc.perform(delete("/api/v1/users/me/interest-categories/${org.id}")
            .queryParam("categoryType", "ORGANIZATION").header("Authorization", bearer(token)))
            .andExpect(status().isNoContent)
        assertTrue(userInterestDomainRepository.findAllByUserId(user.id!!).isEmpty())
    }

    @Test
    fun `interest categories는 인증이 필요하다`() {
        mockMvc.perform(get("/api/v1/users/me/interest-categories")).andExpect(status().isUnauthorized)
        mockMvc.perform(put("/api/v1/users/me/interest-categories").contentType(MediaType.APPLICATION_JSON)
            .content(toJson(mapOf("items" to emptyList<Any>())))).andExpect(status().isUnauthorized)
    }
}
