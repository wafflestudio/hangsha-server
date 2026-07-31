package com.team1.hangsha.batch.job

import com.fasterxml.jackson.databind.ObjectMapper
import com.team1.hangsha.batch.crawler.SnuNowCrawler
import com.team1.hangsha.event.dto.core.CrawledProgramEvent
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

@Component
class SnuNowDumpRunner(
    private val objectMapper: ObjectMapper,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        if (args.getOptionValues("job")?.firstOrNull() !in setOf("snu-now-dump", "snu-calendar-dump")) {
            return
        }

        val opt = SnuNowDumpArgs.from(args)
        val rows = SnuNowCrawler(
            delayMsBetweenPages = opt.delayMs,
            delayMsBetweenDetails = opt.detailDelayMs,
        ).crawl(
            SnuNowCrawler.CrawlOptions(
                startPage = opt.startPage,
                maxPages = opt.maxPages,
                df = opt.df,
                dt = opt.dt,
                qt = opt.qt,
                q = opt.q,
            )
        )

        writeDumpFile(opt.outFile, rows)
        println("Saved SNU Now events to ${Path.of(opt.outFile).toAbsolutePath().normalize()} (count=${rows.size})")
        exitProcess(0)
    }

    private fun writeDumpFile(outFile: String, rows: List<CrawledProgramEvent>) {
        val path = Path.of(outFile).toAbsolutePath().normalize()
        path.parent?.let { Files.createDirectories(it) }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), rows)
    }
}

private data class SnuNowDumpArgs(
    val startPage: Int,
    val maxPages: Int,
    val df: String?,
    val dt: String?,
    val qt: String?,
    val q: String?,
    val delayMs: Long,
    val detailDelayMs: Long,
    val outFile: String,
) {
    companion object {
        fun from(args: ApplicationArguments): SnuNowDumpArgs {
            fun single(name: String): String? = args.getOptionValues(name)?.firstOrNull()

            val maxPages = single("maxPages")?.toIntOrNull()
                ?: throw IllegalArgumentException("--maxPages is required")
            require(maxPages > 0) { "--maxPages must be positive" }

            return SnuNowDumpArgs(
                startPage = single("startPage")?.toIntOrNull() ?: 1,
                maxPages = maxPages,
                df = single("df"),
                dt = single("dt"),
                qt = single("qt"),
                q = single("q"),
                delayMs = single("delayMs")?.toLongOrNull() ?: 200L,
                detailDelayMs = single("detailDelayMs")?.toLongOrNull() ?: 100L,
                outFile = single("outFile") ?: "build/snu-now-events.json",
            )
        }
    }
}
