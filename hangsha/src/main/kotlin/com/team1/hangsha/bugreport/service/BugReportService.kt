package com.team1.hangsha.bugreport.service

import com.team1.hangsha.bugreport.dto.CreateBugReportRequest
import com.team1.hangsha.bugreport.model.BugReport
import com.team1.hangsha.bugreport.notifier.BugReportNotifier
import com.team1.hangsha.bugreport.repository.BugReportRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class BugReportService(
    private val bugReportRepository: BugReportRepository,
    private val bugReportNotifiers: List<BugReportNotifier>,
) {
    private val log = LoggerFactory.getLogger(BugReportService::class.java)

    fun create(req: CreateBugReportRequest, userId: Long?): Long {
        val saved = bugReportRepository.save(
            BugReport(
                userId = userId,
                title = req.title,
                content = req.content,
            )
        )

        bugReportNotifiers.forEach { notifier ->
            try {
                notifier.notify(saved)
            } catch (e: Exception) {
                // B안: 저장은 성공시키고 알림 실패는 로깅만 처리
                log.error("bug-report notify failed. reportId={}, notifier={}", saved.id, notifier.javaClass.simpleName, e)
            }
        }

        return saved.id!!
    }
}
