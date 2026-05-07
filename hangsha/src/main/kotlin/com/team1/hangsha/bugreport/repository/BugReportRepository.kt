package com.team1.hangsha.bugreport.repository

import com.team1.hangsha.bugreport.model.BugReport
import org.springframework.data.repository.CrudRepository

interface BugReportRepository : CrudRepository<BugReport, Long>
