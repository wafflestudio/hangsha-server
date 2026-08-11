package com.team1.hangsha.event.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.team1.hangsha.category.model.Category
import com.team1.hangsha.category.repository.CategoryGroupRepository
import com.team1.hangsha.category.repository.CategoryRepository
import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import com.team1.hangsha.event.dto.core.CrawledDetailSession
import com.team1.hangsha.event.dto.core.CrawledProgramEvent
import com.team1.hangsha.event.dto.request.EventCreateRequest
import com.team1.hangsha.event.dto.request.EventOverrideUpdateRequest
import com.team1.hangsha.event.dto.request.EventPatchRequest
import com.team1.hangsha.event.model.Event
import com.team1.hangsha.event.model.EventPeriodPolicy
import com.team1.hangsha.event.repository.EventRepository
import com.team1.hangsha.search.outbox.EventSearchOutboxWriter
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

@Service
class EventSyncService(
    private val objectMapper: ObjectMapper,
    private val eventRepository: EventRepository,
    private val categoryGroupRepository: CategoryGroupRepository,
    private val categoryRepository: CategoryRepository,
    private val outboxWriter: EventSearchOutboxWriter,
) {

    data class SyncResult(val total: Int, val upserted: Int, val skipped: Int)

    /**
     * 한 TX에서 event에 대한 upsert와 outbox에 대한 이벤트 기록을 원자적으로 수행한다.
     */
    @Transactional
    fun sync(events: List<CrawledProgramEvent>): SyncResult {
        val statusGroupId = requireGroupId("모집현황")
        val typeGroupId = requireGroupId("프로그램 유형")
        val orgGroupId = requireGroupId("주체기관")

        var upserted = 0
        var skipped = 0

        for (e in events) {
            val applyLink = resolveApplyLink(e)

            // If we want to skip re-sync of deleted events, uncomment this block
            //if (eventRepository.existsAdminDeletedByApplyLink(applyLink)) {
            //    skipped++
            //    continue
            //}

            val orgName = e.majorTypes.getOrNull(0)?.trim()?.takeIf { it.isNotBlank() }
            val orgId = orgName?.let { getOrCreateCategoryId(orgGroupId, it) }
            val typeName = normalizeProgramType(e)

            val eventTypeId = typeName?.let { findCategoryId(typeGroupId, it) }

            val applyStart = e.applyStart?.let { dateStart(it) }
            val applyEnd = e.applyEnd?.let { dateEnd(it) }
            val now = LocalDateTime.now(ZoneId.of("Asia/Seoul"))

            val sessions = if (e.isPeriodEvent == true) {
                emptyList()
            } else {
                patchSessionTimesFromMainContent(e.detailSessions, e.mainContentHtml)
            }
            val crawledLocation = e.location?.trim()?.takeIf { it.isNotBlank() }
            val hasExistingForApplyLink = eventRepository.existsByApplyLink(applyLink)

            data class UnitSpec(
                val eventStart: LocalDateTime?,
                val eventEnd: LocalDateTime?,
                val location: String?,
            )

            val unitSpecs: List<UnitSpec> =
                if (sessions.size >= 2) {
                    sessions.map { s ->
                        UnitSpec(
                            eventStart = parseSessionStart(s),
                            eventEnd = parseSessionEnd(s),
                            location = s.location?.trim()?.takeIf { it.isNotBlank() } ?: crawledLocation
                        )
                    }
                } else {
                    val (eventStart, eventEnd) = deriveEventPeriod(e, sessions)
                    listOf(UnitSpec(eventStart, eventEnd, crawledLocation))
                }

            for (spec in unitSpecs) {
                val eventStart = spec.eventStart
                val eventEnd = spec.eventEnd
                val location = spec.location

                val keyStart = eventStart ?: applyStart
                val keyEnd = eventEnd ?: applyEnd

                val existing =
                    if (keyStart != null && keyEnd != null) {
                        eventRepository.findLatestByApplyLinkAndKeyPeriod(
                            applyLink = applyLink,
                            keyStart = keyStart,
                            keyEnd = keyEnd,
                        )
                    } else {
                        null
                    }

                val isAllDayFallbackPeriod = sessions.isEmpty()
                if (existing == null && hasExistingForApplyLink && isAllDayFallbackPeriod) {
                    skipped++
                    continue
                }

                val cleanedTags = e.tags
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .toList()

                val title = e.title!!.trim()
                val crawledIsPeriodEvent = e.isPeriodEvent ?: EventPeriodPolicy.isPeriodEvent(
                    title = title,
                    eventStart = eventStart,
                    eventEnd = eventEnd,
                )
                val statusName = normalizeStatus(
                    raw = e.status,
                    event = e,
                    applyEnd = applyEnd,
                    eventStart = eventStart,
                    eventEnd = eventEnd,
                    now = now,
                )
                val statusId = statusName?.let { findCategoryId(statusGroupId, it) }

                val crawledTagsJson = if (cleanedTags.isEmpty()) {
                    null
                } else {
                    objectMapper.writeValueAsString(cleanedTags)
                }

                val crawledEvent = Event(
                    id = existing?.id,
                    title = title,
                    imageUrl = e.imageUrl?.trim(),
                    operationMode = e.operationMode?.trim(),

                    statusId = statusId,
                    eventTypeId = eventTypeId,
                    orgId = orgId,

                    applyStart = applyStart,
                    applyEnd = applyEnd,
                    eventStart = eventStart,
                    eventEnd = eventEnd,

                    isPeriodEvent = crawledIsPeriodEvent,

                    adminOverriddenFields = existing?.adminOverriddenFields,
                    adminDeleted = false,

                    capacity = e.capacity ?: 0,
                    applyCount = e.applyCount ?: 0,

                    organization = orgName,
                    location = location,
                    applyLink = applyLink,

                    tags = crawledTagsJson,
                    mainContentHtml = e.mainContentHtml,

                    createdAt = existing?.createdAt ?: Instant.now(),
                )

                val model = applyAdminOverrides(
                    crawled = crawledEvent,
                    existing = existing,
                )

                val saved = eventRepository.save(model)
                outboxWriter.upsert(requireNotNull(saved.id))
                upserted++
            }
        }

        return SyncResult(total = events.size, upserted = upserted, skipped = skipped)
    }

    @Transactional
    fun openStartedWaitingEvents(): Int {
        val statusGroupId = requireGroupId("모집현황")
        val waitingStatusId = findCategoryId(statusGroupId, "모집대기")
            ?: throw DomainException(
                ErrorCode.INTERNAL_ERROR,
                "Category not found. group=모집현황, name=모집대기"
            )
        val recruitingStatusId = findCategoryId(statusGroupId, "모집중")
            ?: throw DomainException(
                ErrorCode.INTERNAL_ERROR,
                "Category not found. group=모집현황, name=모집중"
            )

        return eventRepository.openStartedEventsByStatus(
            statusId = waitingStatusId,
            recruitingStatusId = recruitingStatusId,
            now = LocalDateTime.now(ZoneId.of("Asia/Seoul")),
        )
    }

    @Transactional
    fun closeExpiredRecruitingEvents(): Int {
        // @TODO: 굉장히 하드코딩이긴 한데...
        val statusGroupId = requireGroupId("모집현황")

        val recruitingStatusId = findCategoryId(statusGroupId, "모집중")
            ?: throw DomainException(
                ErrorCode.INTERNAL_ERROR,
                "Category not found. group=모집현황, name=모집중"
            )

        val closedStatusId = findCategoryId(statusGroupId, "모집마감")
            ?: throw DomainException(
                ErrorCode.INTERNAL_ERROR,
                "Category not found. group=모집현황, name=모집마감"
            )

        val now = LocalDateTime.now(ZoneId.of("Asia/Seoul"))

        val unknownStatusId = findCategoryId(statusGroupId, "상태 미제공")

        val closedRecruiting = eventRepository.closeExpiredEventsByStatus(
            statusId = recruitingStatusId,
            closedStatusId = closedStatusId,
            now = now,
        )
        val closedUnknown = unknownStatusId?.let {
            eventRepository.closeExpiredEventsByStatus(
                statusId = it,
                closedStatusId = closedStatusId,
                now = now,
            )
        } ?: 0

        return closedRecruiting + closedUnknown
    }

    private fun requireGroupId(name: String): Long {
        val group = categoryGroupRepository.findByName(name)
            ?: throw DomainException(ErrorCode.CATEGORY_GROUP_NOT_FOUND)

        return group.id
            ?: throw DomainException(
                ErrorCode.INTERNAL_ERROR,
                "CategoryGroup.id is null (unexpected). name=$name"
            )
    }

    private fun findCategoryId(groupId: Long, name: String): Long? =
        categoryRepository.findByGroupIdAndName(groupId, name)?.id

    private fun resolveApplyLink(e: CrawledProgramEvent): String {
        e.applyLink?.trim()?.takeIf { it.isNotBlank() }?.let { return it }

        val dataSeq = e.dataSeq?.trim().orEmpty()
        return if (dataSeq.length == 6 && dataSeq.all { it.isDigit() }) {
            "https://www.snu.ac.kr/snunow/events?md=v&bbsidx=$dataSeq"
        } else {
            "https://extra.snu.ac.kr/ptfol/pgm/view.do?dataSeq=$dataSeq"
        }
    }

    private fun deriveEventPeriod(
        e: CrawledProgramEvent,
        sessions: List<CrawledDetailSession>
    ): Pair<LocalDateTime?, LocalDateTime?> {
        if (sessions.isNotEmpty()) {
            val starts = sessions.mapNotNull { parseSessionStart(it) }
            val ends = sessions.mapNotNull { parseSessionEnd(it) }
            val start = starts.minOrNull()
            val end = ends.maxOrNull()
            return start to end
        }

        val start = e.activityStart?.let { LocalDate.parse(it).atStartOfDay() }
        val end = e.activityEnd?.let { LocalDate.parse(it).atTime(23, 59, 59) }
        return start to end
    }

    private fun parseSessionStart(s: CrawledDetailSession): LocalDateTime? {
        val d = s.startDate ?: return null
        val date = LocalDate.parse(d)
        val time = s.startTime?.let { LocalTime.parse(it) } ?: LocalTime.MIDNIGHT
        return date.atTime(time)
    }

    private fun parseSessionEnd(s: CrawledDetailSession): LocalDateTime? {
        val d = s.endDate ?: s.startDate ?: return null
        val date = LocalDate.parse(d)
        val time = s.endTime?.let { LocalTime.parse(it) } ?: LocalTime.of(23, 59, 59)
        return date.atTime(time)
    }

    private fun dateStart(ymd: String): LocalDateTime =
        LocalDate.parse(ymd).atStartOfDay()

    private fun dateEnd(ymd: String): LocalDateTime =
        LocalDate.parse(ymd).atTime(23, 59, 59)

    private fun getOrCreateCategoryId(groupId: Long, rawName: String): Long {
        val name = rawName.trim()
        categoryRepository.findByGroupIdAndName(groupId, name)?.id?.let { return it }

        val nextSortOrder = runCatching { categoryRepository.findMaxSortOrderByGroupId(groupId) + 1 }
            .getOrDefault(1)

        return try {
            val saved = categoryRepository.save(
                Category(
                    groupId = groupId,
                    name = name,
                    sortOrder = nextSortOrder
                )
            )
            saved.id ?: throw DomainException(ErrorCode.CATEGORY_CREATE_FAILED)
        } catch (e: DuplicateKeyException) {
            categoryRepository.findByGroupIdAndName(groupId, name)?.id ?: throw e
        }
    }

    private fun normalizeStatus(
        raw: String?,
        event: CrawledProgramEvent,
        applyEnd: LocalDateTime?,
        eventStart: LocalDateTime?,
        eventEnd: LocalDateTime?,
        now: LocalDateTime,
    ): String? {
        val s = raw?.trim()
        if (s.isNullOrBlank()) return null
        return when (s) {
            "마감임박" -> "모집중"
            "상태 미제공" -> inferUnknownStatus(event, applyEnd, eventStart, eventEnd, now)
            else -> s
        }
    }

    private fun inferUnknownStatus(
        event: CrawledProgramEvent,
        applyEnd: LocalDateTime?,
        eventStart: LocalDateTime?,
        eventEnd: LocalDateTime?,
        now: LocalDateTime,
    ): String {
        val hasOpenApplyPeriod = applyEnd != null && !applyEnd.isBefore(now)
        val eventNotExpired = (eventEnd ?: eventStart)?.let { !it.isBefore(now) } ?: false
        val hasApplyOrSupportText = sequenceOf(
            event.title,
            event.mainContentHtml,
            event.tags.joinToString(" "),
        ).any { it?.contains(Regex("""신청|지원""")) == true }

        return if (hasOpenApplyPeriod || hasApplyOrSupportText || eventNotExpired) {
            "모집중"
        } else {
            "모집마감"
        }
    }

    private fun normalizeProgramType(e: CrawledProgramEvent): String {
        val candidates = buildList {
            addAll(e.majorTypes)
            e.title?.let { add(it) }
            addAll(e.tags)
        }

        if (candidates.any { it.contains("lnl", ignoreCase = true) }) {
            return "OpenLnL"
        }

        val s = e.majorTypes.getOrNull(1)?.trim()
        if (s.isNullOrBlank()) return "기타"

        return when (s) {
            "레크리에이션" -> "기타"
            else -> s
        }
    }

    private val timeRangeRegex = Regex("""\b(\d{1,2}:\d{2})\s*~\s*(\d{1,2}:\d{2})\b""")

    private fun normalizeHm(raw: String): String? {
        val m = Regex("""^(\d{1,2}):(\d{2})$""").find(raw.trim()) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val mi = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || mi !in 0..59) return null
        return "%02d:%02d".format(h, mi)
    }

    private fun patchSessionTimesFromMainContent(
        sessions: List<CrawledDetailSession>,
        mainContentHtml: String?
    ): List<CrawledDetailSession> {
        if (sessions.isEmpty()) return sessions
        if (mainContentHtml.isNullOrBlank()) return sessions
        if (sessions.none { it.startTime == null && it.endTime == null }) return sessions

        val m = timeRangeRegex.find(mainContentHtml) ?: return sessions
        val start = normalizeHm(m.groupValues[1]) ?: return sessions
        val end = normalizeHm(m.groupValues[2]) ?: return sessions

        return sessions.map { s ->
            if (s.startTime == null && s.endTime == null) {
                s.copy(startTime = start, endTime = end)
            } else {
                s
            }
        }
    }

    // @TODO: 하드 코딩이긴 한데, 일단 이렇게 구현.
    private val nonOverridableEventFields = setOf(
        "id",
        "applyLink",
        "createdAt",
        "adminOverriddenFields",
        "adminDeleted",
    )

    private fun normalizeOverrideFields(fields: Iterable<String>): Set<String> {
        return fields
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { it !in nonOverridableEventFields }
            .toSet()
    }

    private fun decodeOverrideFields(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()

        return runCatching {
            objectMapper.readValue(json, object : TypeReference<Set<String>>() {})
        }.getOrDefault(emptySet())
            .let { normalizeOverrideFields(it) }
    }

    private fun encodeOverrideFields(fields: Set<String>): String? {
        val cleaned = normalizeOverrideFields(fields).sorted()
        return if (cleaned.isEmpty()) null else objectMapper.writeValueAsString(cleaned)
    }

    private fun overrideFieldsFromPatch(req: EventPatchRequest): Set<String> {
        val node = objectMapper.valueToTree<ObjectNode>(req)

        return node.fieldNames().asSequence()
            .filter { fieldName ->
                val value = node.get(fieldName)
                value != null && !value.isNull
            }
            .let { normalizeOverrideFields(it.asIterable()) }
    }

    private fun cleanedTagsJson(tags: List<String>?): String? {
        val cleaned = tags?.asSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.toList()
            ?: return null

        return if (cleaned.isEmpty()) null else objectMapper.writeValueAsString(cleaned)
    }

    private fun applyAdminOverrides(
        crawled: Event,
        existing: Event?,
    ): Event {
        if (existing == null) return crawled

        val overrides = decodeOverrideFields(existing.adminOverriddenFields)

        val crawledNode = objectMapper.valueToTree<ObjectNode>(crawled)
        val existingNode = objectMapper.valueToTree<ObjectNode>(existing)

        overrides.forEach { fieldName ->
            val existingValue = existingNode.get(fieldName)
            if (existingValue != null) {
                crawledNode.set<JsonNode>(fieldName, existingValue)
            }
        }

        val merged = objectMapper.treeToValue(crawledNode, Event::class.java)

        return merged.copy(
            id = existing.id,
            createdAt = existing.createdAt,
            adminOverriddenFields = existing.adminOverriddenFields,
            adminDeleted = false,
            isPeriodEvent = if ("isPeriodEvent" in overrides) {
                existing.isPeriodEvent
            } else {
                EventPeriodPolicy.isPeriodEvent(
                    title = merged.title,
                    eventStart = merged.eventStart,
                    eventEnd = merged.eventEnd,
                )
            },
        )
    }

    @Transactional
    fun createEvent(req: EventCreateRequest): Map<String, Any?> {
        val title = req.title.trim().takeIf { it.isNotBlank() }
            ?: throw DomainException(
                ErrorCode.INTERNAL_ERROR,
                "title must not be blank"
            )

        val cleanedTagsJson = req.tags?.let { tags ->
            val cleaned = tags.asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()

            if (cleaned.isEmpty()) null else objectMapper.writeValueAsString(cleaned)
        }

        val derivedIsPeriodEvent = EventPeriodPolicy.isPeriodEvent(
            title = title,
            eventStart = req.eventStart,
            eventEnd = req.eventEnd,
        )
        val organization = req.organization?.trim()?.takeIf { it.isNotBlank() }
        val resolvedOrgId = req.orgId ?: organization?.let {
            getOrCreateCategoryId(requireGroupId("주체기관"), it)
        }

        val units = if (req.sessions.isEmpty()) {
            listOf(Triple(req.eventStart, req.eventEnd, req.location?.trim()))
        } else {
            req.sessions.map { session ->
                Triple(
                    session.start ?: req.eventStart,
                    session.end ?: req.eventEnd,
                    session.location?.trim()?.takeIf { it.isNotBlank() } ?: req.location?.trim(),
                )
            }
        }

        val savedEvents = units.map { (eventStart, eventEnd, location) ->
            eventRepository.save(
                Event(
                    title = title,
                    imageUrl = req.imageUrl?.trim(),
                    operationMode = req.operationMode?.trim(),
                    tags = cleanedTagsJson,
                    mainContentHtml = req.mainContentHtml,
                    statusId = req.statusId,
                    eventTypeId = req.eventTypeId,
                    orgId = resolvedOrgId,
                    applyStart = req.applyStart,
                    applyEnd = req.applyEnd,
                    eventStart = eventStart,
                    eventEnd = eventEnd,
                    isPeriodEvent = if (req.sessions.isNotEmpty()) false else req.isPeriodEvent ?: derivedIsPeriodEvent,
                    capacity = req.capacity ?: 0,
                    applyCount = req.applyCount ?: 0,
                    organization = organization,
                    location = location,
                    applyLink = req.applyLink?.trim(),
                    createdAt = Instant.now(),
                )
            )
        }
        savedEvents.forEach { saved -> outboxWriter.upsert(requireNotNull(saved.id)) }

        return mapOf(
            "ok" to true,
            "eventId" to savedEvents.firstOrNull()?.id,
            "eventIds" to savedEvents.mapNotNull { it.id },
        )
    }

    @Transactional
    fun patchEvent(eventId: Long, req: EventPatchRequest): Map<String, Any> {
        val existing = eventRepository.findById(eventId).orElseThrow {
            DomainException(ErrorCode.EVENT_NOT_FOUND)
        }

        if (existing.adminDeleted) {
            throw DomainException(ErrorCode.EVENT_NOT_FOUND)
        }

        val newOverrideFields =
            decodeOverrideFields(existing.adminOverriddenFields) + overrideFieldsFromPatch(req)

        val cleanedTagsJson = cleanedTagsJson(req.tags)
        val newTitle = req.title?.trim()?.takeIf { it.isNotBlank() } ?: existing.title
        val newEventStart = req.eventStart ?: existing.eventStart
        val newEventEnd = req.eventEnd ?: existing.eventEnd

        val updated = existing.copy(
            title = newTitle,
            imageUrl = req.imageUrl?.trim() ?: existing.imageUrl,
            operationMode = req.operationMode?.trim() ?: existing.operationMode,

            tags = cleanedTagsJson ?: existing.tags,
            mainContentHtml = req.mainContentHtml ?: existing.mainContentHtml,

            statusId = req.statusId ?: existing.statusId,
            eventTypeId = req.eventTypeId ?: existing.eventTypeId,
            orgId = req.orgId ?: existing.orgId,

            applyStart = req.applyStart ?: existing.applyStart,
            applyEnd = req.applyEnd ?: existing.applyEnd,
            eventStart = newEventStart,
            eventEnd = newEventEnd,

            isPeriodEvent = req.isPeriodEvent ?: EventPeriodPolicy.isPeriodEvent(
                title = newTitle,
                eventStart = newEventStart,
                eventEnd = newEventEnd,
            ),

            adminOverriddenFields = encodeOverrideFields(newOverrideFields),
            adminDeleted = false,

            capacity = req.capacity ?: existing.capacity,
            applyCount = req.applyCount ?: existing.applyCount,

            organization = req.organization?.trim() ?: existing.organization,
            location = req.location?.trim() ?: existing.location,
            applyLink = existing.applyLink, // matching key이므로 수정 X
        )

        val saved = eventRepository.save(updated)
        outboxWriter.upsert(saved.id ?: eventId)
        return mapOf("ok" to true, "eventId" to (saved.id ?: eventId))
    }

    @Transactional
    fun deleteEvent(eventId: Long): Map<String, Any> {
        val affected = eventRepository.softDeleteById(eventId)

        if (affected == 0) {
            throw DomainException(ErrorCode.EVENT_NOT_FOUND)
        }

        outboxWriter.delete(eventId)
        return mapOf("ok" to true, "deletedEventId" to eventId)
    }

    @Transactional
    fun updateOverrides(
        eventId: Long,
        req: EventOverrideUpdateRequest,
    ): Map<String, Any> {
        val event = eventRepository.findVisibleById(eventId)
            ?: throw DomainException(ErrorCode.EVENT_NOT_FOUND)

        val current = decodeOverrideFields(event.adminOverriddenFields)

        val next = current +
                normalizeOverrideFields(req.lockFields) -
                normalizeOverrideFields(req.unlockFields)

        val updated = event.copy(
            adminOverriddenFields = encodeOverrideFields(next)
        )

        eventRepository.save(updated)

        return mapOf(
            "ok" to true,
            "eventId" to eventId,
            "adminOverriddenFields" to next.sorted(),
        )
    }
}
