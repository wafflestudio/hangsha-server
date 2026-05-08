package com.team1.hangsha.bugreport.controller

import com.team1.hangsha.bugreport.dto.CreateBugReportRequest
import com.team1.hangsha.bugreport.dto.CreateBugReportResponse
import com.team1.hangsha.bugreport.service.BugReportService
import com.team1.hangsha.user.LoggedInUser
import com.team1.hangsha.user.model.User
import io.swagger.v3.oas.annotations.Parameter
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
    @Operation(
        summary = "버그 리포트 등록",
        description = "로그인 사용자의 버그 리포트를 저장하고, 알림 채널로 전송을 시도합니다. 알림 전송 실패여도 저장은 성공 처리됩니다."
    )
    fun create(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @Valid @RequestBody req: CreateBugReportRequest,
    ): ResponseEntity<CreateBugReportResponse> {
        val id = bugReportService.create(req, user.id)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(CreateBugReportResponse(id = id))
    }
}
