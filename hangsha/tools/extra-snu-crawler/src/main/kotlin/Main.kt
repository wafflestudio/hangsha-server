package org.example

import org.example.crawler.ExtraSnuCrawler
import java.io.File

fun main(args: Array<String>) {
    val opt = Args.parse(args)

    val applyChkCodes = when (opt.crawlMode.lowercase()) {
        "init" -> listOf("0001", "0002", "0003", "0004")
        else -> listOf("0001", "0002", "0004")
    }

    ExtraSnuCrawler(
        delayMsBetweenPages = opt.delayMs,
        delayMsBetweenDetails = opt.detailDelayMs,
        applyChkCodes = applyChkCodes
    ).use { crawler ->

        val baseEvents = when {
            opt.fromFile != null -> {
                val html = File(opt.fromFile).readText(Charsets.UTF_8)
                crawler.parseListHtml(html)
            }
            else -> crawler.crawlAll(startPage = opt.startPage, maxPages = opt.maxPages)
        }

        val events = if (!opt.withDetails) {
            baseEvents
        } else {
            crawler.enrichDetails(baseEvents) { e ->
                if (opt.crawlMode.lowercase() == "init") e.status != "모집마감" else true // @TODO: raw string: ENUM에 넣기?
            }
        }

        val startedAt = System.currentTimeMillis()

        val result = syncEventsToDb(events)

        val elapsedMs = System.currentTimeMillis() - startedAt
        println("Synced ${result.upserted} rows from ${result.total} crawled events (skipped=${result.skipped}) in ${elapsedMs} ms")
    }
}

private data class Args(
    val startPage: Int = 1,
    val maxPages: Int = 500,
    val delayMs: Long = 200,
    val withDetails: Boolean = true,
    val detailDelayMs: Long = 100,
    val crawlMode: String = "sync",
    val fromFile: String? = null
) {
    companion object {
        fun parse(raw: Array<String>): Args {
            var startPage = 1
            var maxPages = 500
            var delayMs = 200L
            var withDetails = true
            var detailDelayMs = 100L
            var crawlMode = "sync"
            var fromFile: String? = null

            for (a in raw) {
                when {
                    a.startsWith("--startPage=") -> startPage = a.substringAfter("=").toInt()
                    a.startsWith("--maxPages=") -> maxPages = a.substringAfter("=").toInt()
                    a.startsWith("--delayMs=") -> delayMs = a.substringAfter("=").toLong()

                    a == "--withDetails" -> withDetails = true
                    a == "--noDetails" -> withDetails = false
                    a.startsWith("--detailDelayMs=") -> detailDelayMs = a.substringAfter("=").toLong()

                    a == "--init" -> crawlMode = "init"
                    a == "--sync" -> crawlMode = "sync"
                    a.startsWith("--crawlMode=") -> crawlMode = a.substringAfter("=")

                    a.startsWith("--fromFile=") -> fromFile = a.substringAfter("=").ifBlank { null }
                }
            }

            return Args(
                startPage = startPage,
                maxPages = maxPages,
                delayMs = delayMs,
                withDetails = withDetails,
                detailDelayMs = detailDelayMs,
                crawlMode = crawlMode,
                fromFile = fromFile
            )
        }
    }
}