package com.team1.hangsha.batch

import com.team1.hangsha.com.team1.hangsha.config.JacksonConfig
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Import

@SpringBootApplication
@Import(
    JacksonConfig::class,
) // for explicit bean import
class BatchApplication

fun main(args: Array<String>) {
    val builder = SpringApplicationBuilder(BatchApplication::class.java)
        .web(WebApplicationType.NONE)

    if (args.any { it == "--job=snu-calendar-dump" }) {
        builder.properties(
            mapOf(
                "spring.autoconfigure.exclude" to listOf(
                    DataSourceAutoConfiguration::class.java.name,
                    DataSourceTransactionManagerAutoConfiguration::class.java.name,
                    JdbcTemplateAutoConfiguration::class.java.name,
                    JdbcRepositoriesAutoConfiguration::class.java.name,
                ).joinToString(",")
            )
        )
    }

    builder.run(*args)
}
