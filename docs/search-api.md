# 통합 검색 API

## 엔드포인트

```
GET /api/v1/events/search
```

## 인증

선택(Optional). `Authorization: Bearer <token>` 헤더를 포함하면 응답에 `isInterested`, `isBookmarked`가 채워집니다. 미포함 시 두 필드 모두 `null`로 반환됩니다.

## 쿼리 파라미터

| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|---|---|---|---|---|
| `query` | string | ✅ | - | 검색어. 공백으로 구분된 단어 각각을 AND 조건으로 검색 |
| `page` | integer | | `1` | 페이지 번호 (1-based) |
| `size` | integer | | `20` | 페이지당 결과 수 |

## 응답

```json
{
  "page": 1,
  "size": 20,
  "total": 8,
  "items": [
    {
      "event": {
        "id": 202,
        "title": "2026-1학기 서울대학교 의료 인공지능 융합인재 양성 사업단 성과교류회",
        "imageUrl": "https://...",
        "operationMode": "오프라인",
        "statusId": 3,
        "eventTypeId": 4,
        "orgId": 26,
        "applyStart": "2026-06-05T00:00:00",
        "applyEnd": "2026-06-19T23:59:59",
        "eventStart": "2026-06-19T14:00:00",
        "eventEnd": "2026-06-19T18:00:00",
        "isPeriodEvent": false,
        "capacity": 0,
        "applyCount": 76,
        "organization": "의료 인공지능 융합인재 양성 사업단",
        "location": "-",
        "applyLink": "https://extra.snu.ac.kr/...",
        "tags": "[\"#의료인공지능\", \"#성과교류회\"]",
        "isInterested": null,
        "matchedInterestPriority": null,
        "isBookmarked": null
      },
      "highlight": {
        "title": "2026-1학기 서울대학교 의료 <mark>인공지능</mark> 융합인재 양성 사업단 성과교류회",
        "contentSnippet": "SNU AI.MED 2026-1학기 관악 성과교류회 개최 안내 2026-1학기 서울대학교 의료 <mark>인공지능</mark> 융합인재 양성 사업단 성과교류회에 여러분을 초대합니다."
      }
    }
  ]
}
```

## 응답 필드 설명

### 최상위

| 필드 | 타입 | 설명 |
|---|---|---|
| `page` | integer | 현재 페이지 번호 |
| `size` | integer | 요청한 페이지 크기 |
| `total` | integer | 전체 매칭 건수 |
| `items` | array | 검색 결과 목록 |

### `items[].event`

기존 캘린더 API의 EventDto와 동일한 구조입니다.

| 필드 | 타입 | 설명 |
|---|---|---|
| `id` | integer | 행사 ID |
| `title` | string | 원본 제목 (하이라이팅 없음) |
| `imageUrl` | string \| null | 썸네일 이미지 URL |
| `operationMode` | string \| null | 운영 방식 (예: `"오프라인"`, `"온라인"`) |
| `statusId` | integer \| null | 상태 카테고리 ID |
| `eventTypeId` | integer \| null | 행사 유형 카테고리 ID |
| `orgId` | integer \| null | 주최 기관 카테고리 ID |
| `applyStart` | datetime \| null | 신청 시작일시 (`yyyy-MM-ddTHH:mm:ss`) |
| `applyEnd` | datetime \| null | 신청 마감일시 |
| `eventStart` | datetime \| null | 행사 시작일시 |
| `eventEnd` | datetime \| null | 행사 종료일시 |
| `isPeriodEvent` | boolean | 기간 행사 여부. `true`이면 `applyStart/End`로 기간 표시 |
| `capacity` | integer \| null | 모집 정원 (`0`이면 정원 미지정) |
| `applyCount` | integer | 신청 인원 |
| `organization` | string \| null | 주최 기관명 |
| `location` | string \| null | 장소 |
| `applyLink` | string \| null | 신청 링크 URL |
| `tags` | string \| null | JSON 배열 문자열 (예: `"[\"#인공지능\", \"#세미나\"]"`) |
| `isInterested` | boolean \| null | 관심 카테고리 해당 여부 (인증 시에만 값 있음) |
| `matchedInterestPriority` | integer \| null | 관심 우선순위 (인증 시에만 값 있음) |
| `isBookmarked` | boolean \| null | 북마크 여부 (인증 시에만 값 있음) |

### `items[].highlight`

| 필드 | 타입 | 설명 |
|---|---|---|
| `title` | string | 검색어가 `<mark>` 태그로 감싸진 제목. 매칭 없어도 항상 반환 |
| `contentSnippet` | string \| null | 본문에서 키워드 주변 ~200자 발췌. `<mark>` 태그 포함. 본문에 매칭이 없으면 하이라이트 없이 맨 앞 ~200자를 대신 반환. 본문 자체가 없으면 `null` |

> **하이라이팅 방식**: raw 입력 단어로 우선 매칭 시도, 매칭 없으면 형태소 분석 결과(KiWi 명사)로 fallback, 그래도 매칭이 없으면 본문 맨 앞부분을 하이라이트 없이 발췌.
> 프론트에서는 `highlight.title`과 `highlight.contentSnippet`을 `innerHTML`로 렌더링하면 됩니다.

## 예시 요청

```
GET /api/v1/events/search?query=인공지능&page=1&size=20
GET /api/v1/events/search?query=서울대%20장학금&page=1&size=20
GET /api/v1/events/search?query=창업%20공모전&page=2&size=10
```

## 에러

| 상황 | 상태 코드 | 설명 |
|---|---|---|
| `query` 파라미터 누락 또는 빈 문자열 | `400` | `"query는 비어있을 수 없습니다"` |
| 검색 결과 없음 | `200` | `total: 0`, `items: []` 정상 반환 |
