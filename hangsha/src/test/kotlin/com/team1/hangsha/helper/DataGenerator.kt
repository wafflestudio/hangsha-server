package com.team1.hangsha.helper

import com.team1.hangsha.category.model.Organization
import com.team1.hangsha.common.enums.CourseSource
import com.team1.hangsha.common.enums.DayOfWeek
import com.team1.hangsha.common.enums.Semester
import com.team1.hangsha.course.model.Course
import com.team1.hangsha.course.model.CourseTimeSlot
import com.team1.hangsha.event.model.Event
import com.team1.hangsha.memo.model.Memo
import com.team1.hangsha.memo.model.MemoTagRef
import com.team1.hangsha.tag.model.Tag
import com.team1.hangsha.timetable.model.Enroll
import com.team1.hangsha.timetable.model.Timetable
import com.team1.hangsha.user.JwtTokenProvider
import com.team1.hangsha.user.model.AuthProvider
import com.team1.hangsha.user.model.AuthTokenPair
import com.team1.hangsha.user.model.User
import com.team1.hangsha.user.model.UserIdentity
import com.team1.hangsha.category.repository.OrganizationRepository
import com.team1.hangsha.course.repository.CourseRepository
import com.team1.hangsha.course.repository.CourseTimeSlotRepository
import com.team1.hangsha.event.repository.EventRepository
import com.team1.hangsha.memo.repository.MemoRepository
import com.team1.hangsha.tag.repository.TagRepository
import com.team1.hangsha.timetable.repository.EnrollRepository
import com.team1.hangsha.timetable.repository.TimetableRepository
import com.team1.hangsha.user.repository.UserIdentityRepository
import com.team1.hangsha.user.repository.UserInterestDomainRepository
import com.team1.hangsha.user.model.InterestCategoryType
import com.team1.hangsha.user.repository.UserRepository
import org.mindrot.jbcrypt.BCrypt
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

@Component
class DataGenerator(
    private val userRepository: UserRepository,
    private val userIdentityRepository: UserIdentityRepository,
    private val userInterestDomainRepository: UserInterestDomainRepository,
    private val jwtTokenProvider: JwtTokenProvider,

    private val organizationRepository: OrganizationRepository,

    private val eventRepository: EventRepository,

    private val tagRepository: TagRepository,
    private val memoRepository: MemoRepository,

    private val timetableRepository: TimetableRepository,
    private val courseRepository: CourseRepository,
    private val courseTimeSlotRepository: CourseTimeSlotRepository,
    private val enrollRepository: EnrollRepository,
) {
    companion object {
        private val seq = AtomicLong(1L)
    }

    private fun next(): Long = seq.getAndIncrement()

    // ----------------------------
    // Users / Auth
    // ----------------------------

    fun generateLocalUser(
        username: String? = null,
        email: String? = null,
        rawPassword: String? = null,
    ): Pair<User, AuthTokenPair> {
        val n = next()

        val u = userRepository.save(
            User(
                username = username ?: "user-$n",
                email = email ?: "user$n@test.com",
                profileImageUrl = null,
            ),
        )

        userIdentityRepository.save(
            UserIdentity(
                userId = u.id!!,
                provider = AuthProvider.LOCAL,
                providerUserId = null,
                email = u.email,
                password = BCrypt.hashpw(rawPassword ?: "pw-$n", BCrypt.gensalt()),
            ),
        )

        val tokens = AuthTokenPair(
            accessToken = jwtTokenProvider.createAccessToken(u.id!!),
            refreshToken = jwtTokenProvider.createRefreshToken(u.id!!),
        )

        return u to tokens
    }

    fun generateSocialUser(
        provider: AuthProvider,
        providerUserId: String? = null,
        username: String? = null,
        email: String? = null,
    ): Pair<User, AuthTokenPair> {
        val n = next()

        val u = userRepository.save(
            User(
                username = username ?: "${provider.name.lowercase()}-$n",
                email = email ?: "social$n@test.com",
                profileImageUrl = null,
            ),
        )

        userIdentityRepository.save(
            UserIdentity(
                userId = u.id!!,
                provider = provider,
                providerUserId = providerUserId ?: "${provider.name.lowercase()}-$n",
                email = u.email,
                password = null,
            ),
        )

        val tokens = AuthTokenPair(
            accessToken = jwtTokenProvider.createAccessToken(u.id!!),
            refreshToken = jwtTokenProvider.createRefreshToken(u.id!!),
        )

        return u to tokens
    }

    fun generateUserWithAccessToken(): Pair<User, String> {
        val (u, tokens) = generateLocalUser()
        return u to tokens.accessToken
    }

    // ----------------------------
    // Categories
    // ----------------------------

    /**
     * orgId 필터용 카테고리 필요할 때 사용.
     * repo에 findByName이 없을 수 있으니 "그냥 새 그룹을 만들어도 테스트는 충분"하게 설계.
     */
    fun generateOrgCategory(name: String? = null): Organization {
        val n = next()
        return organizationRepository.save(Organization(name = name ?: "organization-$n", sortOrder = n.toInt()))
    }

    // ----------------------------
    // User interests
    // ----------------------------

    fun addUserInterestCategory(user: User, organization: Organization, priority: Int = 1) {
        userInterestDomainRepository.add(user.id!!, InterestCategoryType.ORGANIZATION, organization.id!!, priority)
    }

    // ----------------------------
    // Events
    // ----------------------------

    fun generateEvent(
        title: String? = null,
        imageUrl: String? = null,
        operationMode: String? = null,
        tags: String? = null,
        mainContentHtml: String? = null,
        statusId: Long? = null,
        eventTypeId: Long? = null,
        orgId: Long? = null,
        applyStart: LocalDateTime? = null,
        applyEnd: LocalDateTime? = null,
        eventStart: LocalDateTime? = null,
        eventEnd: LocalDateTime? = null,
        capacity: Int? = null,
        applyCount: Int? = null,
        organization: String? = null,
        location: String? = null,
        applyLink: String? = null,
    ): Event {
        val n = next()
        val now = LocalDateTime.now()

        val resolvedOrgId = orgId ?: generateOrgCategory().id!!

        return eventRepository.save(
            Event(
                title = title ?: "event-$n",
                imageUrl = imageUrl,
                operationMode = operationMode,
                tags = tags,
                mainContentHtml = mainContentHtml,

                statusId = statusId,
                eventTypeId = eventTypeId,
                orgId = resolvedOrgId,

                applyStart = applyStart ?: now.minusDays(3),
                applyEnd = applyEnd ?: now.plusDays(7),
                eventStart = eventStart ?: now.plusDays(10),
                eventEnd = eventEnd ?: now.plusDays(10).plusHours(2),

                capacity = capacity,
                applyCount = applyCount ?: Random.nextInt(0, 50),

                organization = organization,
                location = location,
                applyLink = applyLink,
            ),
        )
    }

    // ----------------------------
    // Tags / Memos
    // ----------------------------

    fun generateTag(userId: Long, name: String? = null): Tag {
        val n = next()
        return tagRepository.save(
            Tag(userId = userId, name = name ?: "tag-$n"),
        )
    }

    /**
     * @MappedCollection(tags) 기반이라 Memo 저장 시 tagRefs를 넣어주면 memo_tags에 같이 반영됨.
     * - tagIds는 이미 생성된 Tag들의 id를 받는게 가장 안전(테스트에서 명시적)
     */
    fun generateMemo(
        user: User? = null,
        event: Event? = null,
        content: String? = null,
        tagIds: List<Long> = emptyList(),
    ): Memo {
        val n = next()
        val u = user ?: generateUserWithAccessToken().first
        val e = event ?: generateEvent()

        val tagRefs = tagIds.map { MemoTagRef(tagId = it) }.toSet()

        return memoRepository.save(
            Memo(
                userId = u.id!!,
                eventId = e.id!!,
                content = content ?: "memo-$n",
                tags = tagRefs,
            ),
        )
    }

    // ----------------------------
    // Courses / CourseTimeSlot
    // ----------------------------

    fun generateCourse(
        year: Int? = null,
        semester: Semester? = null,
        courseTitle: String? = null,
        source: CourseSource? = null,
        ownerUser: User? = null,
        courseNumber: String? = null,
        lectureNumber: String? = null,
        credit: Int? = null,
        instructor: String? = null,
    ): Course {
        val n = next()
        val y = year ?: 2025
        val s = semester ?: Semester.FALL
        val src = source ?: CourseSource.CUSTOM

        // CUSTOM이면 ownerUserId를 자동으로 채워주면 실수 줄어듦
        val resolvedOwnerUserId =
            when (src) {
                CourseSource.CUSTOM -> (ownerUser?.id ?: generateUserWithAccessToken().first.id!!)
                CourseSource.CRAWLED -> null
            }

        return courseRepository.save(
            Course(
                year = y,
                semester = s,
                courseTitle = courseTitle ?: "강의-$n",
                source = src,
                ownerUserId = resolvedOwnerUserId,

                // ✅ 서로 다른 값 자동 생성
                courseNumber = courseNumber ?: "C-$y-${n.toString().padStart(5, '0')}",
                lectureNumber = lectureNumber ?: ((n % 3) + 1).toString().padStart(3, '0'),
                credit = credit ?: 3,
                instructor = instructor ?: "교수-$n",
            ),
        )
    }

    fun generateCourseTimeSlot(
        course: Course,
        dayOfWeek: DayOfWeek? = null,
        startAt: Int? = null,
        endAt: Int? = null,
    ): CourseTimeSlot {
        val n = next()

        val days = listOf(DayOfWeek.MON, DayOfWeek.TUE, DayOfWeek.WED, DayOfWeek.THU, DayOfWeek.FRI)
        val resolvedDay = dayOfWeek ?: days[(n % days.size).toInt()]

        // 09:00, 10:30, 12:00, 13:30, 15:00 같은 식으로 순환
        val baseStart = startAt ?: (9 * 60 + ((n % 5).toInt() * 90))
        val baseEnd = endAt ?: (baseStart + 75)

        return courseTimeSlotRepository.save(
            CourseTimeSlot(
                courseId = course.id!!,
                dayOfWeek = resolvedDay,
                startAt = baseStart,
                endAt = baseEnd,
            ),
        )
    }

    // ----------------------------
    // Timetables / Enrolls
    // ----------------------------

    fun generateTimetable(
        user: User? = null,
        name: String? = null,
        year: Int? = null,
        semester: Semester? = null,
    ): Timetable {
        val n = next()
        val u = user ?: generateUserWithAccessToken().first
        return timetableRepository.save(
            Timetable(
                userId = u.id!!,
                name = name ?: "timetable-$n",
                year = year ?: 2025,
                semester = semester ?: Semester.FALL,
            ),
        )
    }

    fun generateEnroll(
        timetable: Timetable,
        course: Course,
    ): Enroll {
        return enrollRepository.save(
            Enroll(
                timetableId = timetable.id!!,
                courseId = course.id!!,
            ),
        )
    }

    // ----------------------------
    // Cleanup
    // ----------------------------

    fun cleanupAll() {
        // 자식 -> 부모 순서
        enrollRepository.deleteAll()
        courseTimeSlotRepository.deleteAll()
        timetableRepository.deleteAll()
        courseRepository.deleteAll()

        memoRepository.deleteAll()
        tagRepository.deleteAll()

        userInterestDomainRepository.deleteAll()

        eventRepository.deleteAll()
        organizationRepository.deleteAll()

        userIdentityRepository.deleteAll()
        userRepository.deleteAll()
    }
}
