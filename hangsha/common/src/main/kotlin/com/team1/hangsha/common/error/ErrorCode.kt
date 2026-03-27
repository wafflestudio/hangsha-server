package com.team1.hangsha.common.error

import org.springframework.http.HttpStatus

enum class ErrorCode(
    val httpStatus: HttpStatus,
    val message: String
) {
    // Common
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다"),

    // Auth
    AUTH_INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다"),
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다"),
    AUTH_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다"),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다"),

    // User
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다"),
    USER_EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다"),

    // Password policy
    PASSWORD_TOO_SHORT(HttpStatus.BAD_REQUEST, "비밀번호는 8자 이상이어야 합니다"),
    PASSWORD_WEAK(HttpStatus.BAD_REQUEST, "비밀번호는 영문, 숫자, 특수문자를 모두 포함해야 합니다"),
    PASSWORD_CONTAINS_WHITESPACE(HttpStatus.BAD_REQUEST, "비밀번호에 공백을 사용할 수 없습니다"),

    // Events
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "이벤트를 찾을 수 없습니다"),

    // Preference
    PREFERENCE_INTEREST_CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "관심 카테고리를 찾을 수 없습니다"),
    PREFERENCE_CATEGORY_NOT_FOUND(HttpStatus.BAD_REQUEST, "카테고리가 존재하지 않습니다"),
    PREFERENCE_PRIORITY_INVALID(HttpStatus.BAD_REQUEST, "우선순위 값이 올바르지 않습니다"),

    // Category
    CATEGORY_GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "카테고리 그룹을 찾을 수 없습니다"),
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "카테고리를 찾을 수 없습니다"),
    CATEGORY_CREATE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "카테고리 생성에 실패했습니다"),

    // Tag
    TAG_NOT_FOUND(HttpStatus.NOT_FOUND, "태그를 찾을 수 없습니다"),
    TAG_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 존재하는 태그 이름입니다"),

    // Memo
    MEMO_NOT_FOUND(HttpStatus.NOT_FOUND, "메모를 찾을 수 없습니다"),

    // Timetable / Enroll / Course
    TIMETABLE_NOT_FOUND(HttpStatus.NOT_FOUND, "시간표를 찾을 수 없습니다"),
    TIMETABLE_NAME_CANNOT_BE_BLANK(HttpStatus.BAD_REQUEST, "시간표 이름은 비워둘 수 없습니다"),
    TIMETABLE_TERM_MISMATCH(HttpStatus.BAD_REQUEST, "시간표의 학기/년도와 일치하지 않습니다"),

    ENROLL_NOT_FOUND(HttpStatus.NOT_FOUND, "시간표에 등록된 강의를 찾을 수 없습니다"),
    ENROLL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 시간표에 추가된 강의입니다"),
    ENROLL_PATCH_EMPTY(HttpStatus.BAD_REQUEST, "수정할 항목이 없습니다"),
    ENROLL_TIME_CONFLICT(HttpStatus.CONFLICT, "시간이 중복되는 강의입니다."),
    COURSE_NOT_FOUND(HttpStatus.NOT_FOUND, "강의를 찾을 수 없습니다"),
    COURSE_NOT_EDITABLE(HttpStatus.FORBIDDEN, "수정할 수 없는 강의입니다"),

    COURSE_TITLE_CANNOT_BE_NULL(HttpStatus.BAD_REQUEST, "강의명은 null일 수 없습니다"),
    COURSE_TITLE_CANNOT_BE_BLANK(HttpStatus.BAD_REQUEST, "강의명은 비워둘 수 없습니다"),

    TIME_SLOTS_REQUIRED(HttpStatus.BAD_REQUEST, "강의 시간 정보가 필요합니다"),
    TIME_SLOTS_CANNOT_BE_NULL(HttpStatus.BAD_REQUEST, "강의 시간 정보는 null일 수 없습니다"),
    TIME_SLOTS_CANNOT_BE_EMPTY(HttpStatus.BAD_REQUEST, "강의 시간 정보는 비워둘 수 없습니다"),

    // Upload
    UPLOAD_FILE_EMPTY(HttpStatus.BAD_REQUEST, "업로드할 파일이 없습니다"),
    UPLOAD_UNSUPPORTED_MEDIA_TYPE(HttpStatus.BAD_REQUEST, "지원하지 않는 파일 형식입니다"),
    UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "파일 업로드에 실패했습니다"),
}