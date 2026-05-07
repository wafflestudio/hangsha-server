package com.team1.hangsha.bugreport.service

import com.team1.hangsha.bugreport.dto.CreateBugReportRequest
import com.team1.hangsha.bugreport.model.BugReport
import com.team1.hangsha.bugreport.repository.BugReportRepository
import org.springframework.stereotype.Service

@Service
class BugReportService(
    private val bugReportRepository: BugReportRepository,
) {
    fun create(req: CreateBugReportRequest): Long {
        val saved = bugReportRepository.save(
            BugReport(
                title = req.title,
                content = req.content,
            )
        )
        return saved.id!!
    }
}
