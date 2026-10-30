# Discord 행사 관리 명령

Discord Developer Portal에서 Application의 **Interactions Endpoint URL**을
`https://<api-host>/api/v1/discord/interactions`로 설정한다. 운영 Public Key는 서버
`application.yml`의 prod 프로필에 있으며, 필요하면 `DISCORD_APPLICATION_PUBLIC_KEY`로 덮어쓸 수 있다. 이 엔드포인트는 Discord Ed25519 서명만
허용한다.

서명 timestamp는 5분 이내여야 하며, interaction ID는 DB에서 한 번만 처리한다.

다음 application command를 등록한다.

| Command | Required options |
| --- | --- |
| `event-create` | `title` (String), 필요한 행사 필드를 선택 옵션으로 입력 |
| `event-patch` | `event_id` (Integer), 변경할 행사 필드를 선택 옵션으로 입력 |
| `event-delete` | `event_id` (Integer) |
| `event-detail` | `event_id` (Integer): 조회할 행사 ID |
| `event-search` | `query` (String): 제목·본문 검색어 |

등록할 전체 명령 스키마는 [discord-guild-commands.json](discord-guild-commands.json)에 있다.
길드 명령 등록 시 이 파일 전체를 사용하여 기존 생성·수정·삭제 명령도 유지한다.

`event-search`는 선택 옵션 `page`(기본 1), `status_id`, `event_type_id`, `org_id`를 지원한다.
페이지당 5건이며, 기존 `EventService.search`의 한국어 형태소·부분 일치 검색,
상태·유형·기관 필터 및 마감순 정렬을 그대로 사용한다. Discord 계정을 행샤 계정에
연결하지 않으므로 개인 관심사·북마크·제외 키워드는 적용하지 않는다.

검색 결과에 표시된 ID를 `/event-detail event_id:123`에 넣어 상세 내용을 확인한 뒤
같은 ID로 수정·삭제할 수 있다. 생성 성공 응답에도 새 행사 ID가 포함된다.
관리자가 삭제한 행사는 기존 조회 서비스와 동일하게 조회·검색에서 제외된다.
응답은 명령 실행자에게만 보이며, 본문의 HTML은 텍스트로 표시한다.
긴 내용은 Discord 메시지 길이에 맞춰 생략한다.

```text
/event-search query:장학금 page:1
/event-detail event_id:123
```

예시:

```text
/event-create title:새 행사 organization:학생처 event_start:2026-09-03T10:00:00 event_end:2026-09-03T12:00:00
/event-patch event_id:123 location:문화관 apply_link:https://example.com/apply
```

생성·수정에서 지원하는 필드는 `title`, `main_content`, `event_type_id`, `org_id`,
`apply_start`, `apply_end`, `event_start`, `event_end`, `is_period_event`, `organization`,
`location`, `apply_link`이다. 날짜와 시간은 `2026-10-10T14:00:00` 형식으로 입력한다.
수정에서는 전달한 필드만 변경하며 `apply_link`도 수정할 수 있다.
