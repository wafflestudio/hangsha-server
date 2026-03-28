package com.team1.hangsha.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import jakarta.annotation.PostConstruct

@Component
class TestValueLogger(
    @Value("\${test:}") private val testValue: String,
) {
    private val log = LoggerFactory.getLogger(TestValueLogger::class.java)

    @PostConstruct
    fun logTestValue() {
        log.info("[test-value] test={}", testValue)
    }
}
