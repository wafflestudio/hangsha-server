package com.team1.hangsha.search

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/search")
class SearchAdminController(
    private val manticoreIndexService: ManticoreIndexService,
) {
    @PostMapping("/reindex")
    fun reindex(): Map<String, Any> = manticoreIndexService.reindexAll()
}
