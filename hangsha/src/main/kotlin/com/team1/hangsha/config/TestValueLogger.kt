package com.team1.hangsha.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.boot.context.event.ApplicationReadyEvent

@Component
class TestValueLogger(
    @Value("\${test:}") private val testValue: String,
) {
    private val log = LoggerFactory.getLogger(TestValueLogger::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun logTestValue() {
        log.info("[test-value] test={}", testValue)
    }
}
