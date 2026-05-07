package com.team1.hangsha.bugreport.controller

import com.team1.hangsha.bugreport.dto.CreateBugReportRequest
import com.team1.hangsha.bugreport.dto.CreateBugReportResponse
import com.team1.hangsha.bugreport.service.BugReportService
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/bug-reports")
class BugReportController(
    private val bugReportService: BugReportService,
) {
    @PostMapping
    @Operation(summary = "버그 리포트 등록")
    fun create(
        @Valid @RequestBody req: CreateBugReportRequest,
    ): ResponseEntity<CreateBugReportResponse> {
        val id = bugReportService.create(req)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(CreateBugReportResponse(id = id))
    }
}
