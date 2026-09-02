# Discord 행사 관리 명령

Discord Developer Portal에서 Application의 **Interactions Endpoint URL**을
`https://<api-host>/api/v1/discord/interactions`로 설정하고, Public Key를 배포 환경의
`DISCORD_APPLICATION_PUBLIC_KEY`에 설정한다. 이 엔드포인트는 Discord Ed25519 서명만
허용한다.

다음 application command를 등록한다.

| Command | Required options |
| --- | --- |
| `event-create` | `payload` (String): admin 생성 API와 동일한 `EventCreateRequest` JSON |
| `event-patch` | `event_id` (Integer), `payload` (String): admin 수정 API와 동일한 `EventPatchRequest` JSON |
| `event-delete` | `event_id` (Integer) |

예시:

```json
{"title":"새 행사","organization":"학생처","eventStart":"2026-09-03T10:00:00","eventEnd":"2026-09-03T12:00:00","tags":["특강"]}
```

`event-patch`의 payload는 admin page와 마찬가지로 null을 변경하지 않는 값으로 취급한다.
전송한 non-null 필드는 `adminOverriddenFields`에 기록된다. `applyLink`는 기존 admin API와
동일하게 수정하지 않는다.
