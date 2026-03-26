package com.team1.hangsha.batch

import com.team1.hangsha.config.DatabaseConfig
import com.team1.hangsha.com.team1.hangsha.config.JacksonConfig
import com.team1.hangsha.event.service.EventSyncService
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Import

@SpringBootApplication
@Import(
    DatabaseConfig::class,
    JacksonConfig::class,
    EventSyncService::class,
)
class BatchApplication

fun main(args: Array<String>) {
    SpringApplicationBuilder(BatchApplication::class.java)
        .web(WebApplicationType.NONE)
        .run(*args)
}
