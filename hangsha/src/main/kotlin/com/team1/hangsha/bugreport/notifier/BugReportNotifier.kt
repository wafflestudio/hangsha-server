package com.team1.hangsha.bugreport.notifier

import com.team1.hangsha.bugreport.model.BugReport

interface BugReportNotifier {
    fun notify(report: BugReport)
}
