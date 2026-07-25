# mail-poc — 서울대 대량메일발송 시스템 수집 POC

행샤의 행사 소스를 하나 더 늘리기 위한 검증용 코드다.
**서울대 대량메일발송 시스템(ThunderMail)이 보낸 메일에서 행사 정보를 추출할 수 있는지**를 확인한다.

결론부터: **가능하다.** 최근 400통 중 대량메일 86통을 식별했고, 최신 30건을 CSV로 뽑아
본문·포스터·신청링크가 모두 회수되는 것을 확인했다. 자세한 수치는 [결과](#결과) 참고.

---

## 크롤링 방식

### 왜 웹 스크래핑이 아니라 IMAP인가

대량메일발송 시스템 본체는 **`letter2.snu.ac.kr`** 이다 (title: `ThunderMail5.0`, 학교 IP `147.46.10.217`).
그런데 이건 **발신자(부서 담당자)용 관리 도구**라서 로그인 셸(234바이트)만 응답하고
발송 이력 공개 아카이브가 없다. 즉 시스템을 직접 긁는 경로는 성립하지 않는다.

Gmail 화면을 Playwright로 긁는 방법도 검토했으나 배제했다:
Google이 자동화 브라우저 로그인을 차단하고, 세션 만료 시 2차 인증이 필요해
**서버에서 무인으로 도는 배치**로는 구조적으로 부적합하다. 게다가 본문 HTML 원문이 필요한데
그건 렌더링된 화면에서 되돌리기 가장 어려운 형태다.

그래서 **그 시스템이 보낸 메일이 도착한 메일함을 IMAP으로 읽는다.** 메일 클라이언트가
하는 일과 동일하고, MIME 원문을 그대로 받으므로 발신자가 보낸 HTML이 손실 없이 들어온다.

### `@snu.ac.kr` 메일은 시스템이 하나가 아니다

SPF 레코드에 세 개가 다 들어있고, 소속에 따라 메일함 위치가 갈린다:

```
v=spf1 include:_spf.snu.ac.kr include:_spf2.snu.ac.kr
       include:_spf.gov-dooray.com          ← 교직원·대학원생 (Dooray)
       include:_spf.google.com              ← 학부생 (Google Workspace)
       include:spf.protection.outlook.com   ← Microsoft 365 테넌트
       ~all
```

| 시스템 | 대상 | IMAP 가능 여부 |
|---|---|---|
| **Google Workspace** (`imap.gmail.com`) | 학부생 | **가능** — `AUTH=PLAIN` + 앱 비밀번호, `X-GM-EXT-1` 지원 |
| Dooray (`imap.gov-dooray.com`) | 교직원·대학원생 | 로그인이 학교 SSO(`/auth/signin/process/iam`)에 위임됨. 학부생은 계정 자체가 없어 로그인 화면이 "교직원 및 대학원생 / 손님"만 제시 |
| Microsoft 365 (`outlook.office365.com`) | 일부 | `AUTH=XOAUTH2`만 실동작. MS가 비밀번호 IMAP을 폐지 → Graph API 필요 |

기본 호스트는 `imap.gmail.com`이다. 다른 시스템은 `SNU_IMAP_HOST`로 덮어쓴다.

수신 경로는 `snu.ac.kr` MX가 gov-dooray 단일이라, **gov-dooray가 받아서 Gmail로 자동전달**한다.
`Return-Path: <noreply+fwd<아이디>=snu.ac.kr@dooray.com>`이 그 증거다.

### 프로토콜 흐름

```
1. TLS 연결       imap.gmail.com:993
2. LOGIN          SASL PLAIN (아이디 + 앱 비밀번호)   ← 2차 인증 프롬프트 없음
3. SELECT INBOX   readonly=True                      ← 쓰기 불가 모드
4. UID SEARCH     ALL → 최근 N개(--scan)로 자름
5. UID FETCH      BODY.PEEK[HEADER.FIELDS (X-MAILER)]  ← 200통씩 배치, 헤더만
6. 로컬 필터       X-Mailer 로 대량메일 판정
7. UID FETCH      (X-GM-MSGID BODY.PEEK[])           ← 선별된 것만 원문 수신
8. 로컬 파싱       MIME → 본문 HTML → 텍스트/포스터/링크
```

### 대량메일 식별 방법

**`X-Mailer` 헤더가 가장 안정적이다.** 제목 규칙은 언제든 바뀐다.

| 신호 | 값 | 용도 |
|---|---|---|
| `X-Mailer` | `ThunderMail5.0`, `bizenic_sender` | **최우선 필터.** 학교가 발송 시스템을 2개 쓴다 |
| SPF domain | `thundermail.kr` | 보조 확인 |
| 본문 링크 호스트 | `letter2.snu.ac.kr` | 강한 신호 |
| 제목 접두사 | `[경력개발센터]`, `[교육]`, `[모집/Recruitment]` … | 발신 부서·카테고리가 그대로 들어있음 |
| `Return-Path` | 전부 동일 (`noreply+fwd...@dooray.com`) | **식별에 못 씀** |

### 개인정보 스크러빙과 추적 URL 언랩

대량메일 본문 링크에는 **수신자 이메일이 박혀 있다.** 그대로 저장하면 안 된다.

```
letter2.snu.ac.kr/response/response.do?...&EMA=<base64>&...
                                            └─ 디코딩하면 수신자 메일 주소가 그대로 나온다
letter2.snu.ac.kr/reject_list.jsp?email=<해시>            ← 수신거부 해시
```

그래서 수집 시 세 가지를 한다:

1. **스크러빙** — `EMA`/`email` 등 수신자 식별 파라미터 제거. 값이 base64 이메일인지도 검사해서
   파라미터명이 다르더라도 잡아낸다 (`scrub_url`, `decodes_to_email`)
2. **수신거부 링크 폐기** — `reject_list` / `unsubscribe` 링크는 계정 해시가 들어있고
   행사 정보와 무관하므로 통째로 버린다
3. **추적 URL 언랩** — `response.do?...&URL=<실제주소>`에서 목적지를 꺼내고 추적 URL은 버린다
   (`unwrap_tracking_url`)

### 안전장치

- 메일함은 항상 `readonly`로 열고 `BODY.PEEK`만 쓴다 → **읽음 처리되지 않는다**
- 쓰기/삭제/이동 명령 없음
- 표준 라이브러리만 사용 (의존성 설치 불필요)
- 비밀번호는 `.env` 또는 환경변수로만 주입하고 `.gitignore`에 등록됨
- `out/` 결과물도 `.gitignore` 대상 (개인 메일 내용이 들어감)

---

## 설정

### 1. Google 앱 비밀번호 발급

Gmail은 일반 로그인 비밀번호를 IMAP에 받지 않는다. **앱 비밀번호**가 필요하다.

1. **2단계 인증을 켠다** — 앱 비밀번호 발급의 전제조건이다.
   안 켜져 있으면 발급 메뉴 자체가 숨겨진다: https://myaccount.google.com/signinoptions/twosverification
2. https://myaccount.google.com/apppasswords 에서 발급 (앱 이름은 아무거나)
3. 나오는 **16자리**를 복사한다. 화면을 닫으면 다시 볼 수 없다.

앱 비밀번호 메뉴가 2단계 인증을 켠 뒤에도 안 보이면 SNU Workspace 관리자가 차단한 것이고,
그 경우 Gmail API(OAuth2)로 가야 한다.

### 2. `.env` 작성

```bash
cd mail-poc
cat > .env <<'EOF'
SNU_MAIL_USER=본인아이디@snu.ac.kr
SNU_MAIL_APP=앱비밀번호16자리
EOF
chmod 600 .env
```

| 환경변수 | 필수 | 설명 |
|---|---|---|
| `SNU_MAIL_USER` | ✅ | 메일 주소 전체 |
| `SNU_MAIL_APP` | ✅ | 앱 비밀번호. `SNU_MAIL_APP_PASSWORD` / `SNU_MAIL_PASSWORD` 도 인식한다 |
| `SNU_IMAP_HOST` | | 기본 `imap.gmail.com`. Dooray/M365 확인 시 덮어쓴다 |
| `SNU_IMAP_PORT` | | 기본 `993` |

Google이 앱 비밀번호를 `abcd efgh ijkl mnop`처럼 공백을 넣어 보여주는데,
**그대로 붙여넣어도 스크립트가 공백을 제거**한다. 따옴표도 처리한다.

`.env` 없이 환경변수로 한 번만 돌리는 것도 된다 (셸 히스토리에 남는 점은 감수):

```bash
SNU_MAIL_USER=... SNU_MAIL_APP=... python3 imap_poc.py probe
```

---

## 실행

```bash
# 1) 로그인 / 서버 기능 / 폴더 목록 확인
python3 imap_poc.py probe

# 2) 최근 메일 헤더를 훑어 대량메일 식별 단서 찾기
python3 imap_poc.py headers --days 30 --limit 40
python3 imap_poc.py headers --query "from:snu.ac.kr 안내" --limit 40   # Gmail 검색 문법

# 3) 특정 메일의 전체 헤더 + MIME 구조
python3 imap_poc.py raw 9219

# 4) 본문 HTML / 이미지를 out/uid-9219/ 로 저장
python3 imap_poc.py dump 9219

# 5) 대량메일만 골라 CSV 로 저장  ← 메인 산출물
python3 imap_poc.py csv --count 30 --scan 400
```

| 커맨드 | 옵션 | 산출물 |
|---|---|---|
| `probe` | — | 화면 출력 |
| `headers` | `--days` `--limit` `--query` `--folder` | `out/headers.json` |
| `raw` | `<uid>` `--folder` | 화면 출력 |
| `dump` | `<uid>` `--folder` | `out/uid-<uid>/` (`headers.json`, `body.html`, `body.txt`, `images/`) |
| `csv` | `--count` `--scan` `--out` `--folder` | `out/bulkmail.csv` |

`--scan`은 헤더를 훑을 최근 메일 수, `--count`는 CSV에 담을 대량메일 건수다.
대량메일 비율이 낮은 기간을 다루려면 `--scan`을 늘린다.

---

## 결과

### 접속 (`probe`)

```
[LOGIN OK]  imap.gmail.com
[GMAIL] X-GM-EXT-1 지원 → Gmail 검색 문법(X-GM-RAW) / 라벨 조회 가능
[CAPABILITY] IMAP4REV1 UNSELECT IDLE NAMESPACE QUOTA ID XLIST CHILDREN
             X-GM-EXT-1 XYZZY SASL-IR AUTH=XOAUTH2 AUTH=PLAIN ...
[FOLDERS] 11개 (한글 폴더명·✔ 라벨 mUTF-7 디코딩 정상)
[INBOX] 총 9,121통
```

### 대량메일 식별 (`csv`)

```
[SCAN] 최근 400통 중 대량메일 86통: {'ThunderMail5.0': 81, 'bizenic_sender': 5}
```

### CSV 산출물 (`out/bulkmail.csv`)

최신 30건 (2026-07-16 ~ 07-24), 12컬럼, 81KB. Excel 한글 대응을 위해 UTF-8 BOM.

```
포스터 없음: 8/30
신청링크 없음: 3/30
본문 200자 미만: 0/30      ← 포스터만 있어 파싱 불가한 메일은 없었다
```

| 컬럼 | 내용 |
|---|---|
| `uid` | IMAP UID. 폴더 내에서만 유효 |
| `gmail_msgid` | `X-GM-MSGID`. Gmail 영구 ID → **중복 판정 키 후보** |
| `date` | Asia/Seoul ISO8601 |
| `mailer` | `ThunderMail5.0` / `bizenic_sender` |
| `from_name`, `from_email` | 발신 부서·담당자 |
| `category` | 제목 접두사 (`[경력개발센터]` → `경력개발센터`) |
| `title` | 접두사 제거한 제목 |
| `poster_url` | `letter2.snu.ac.kr/upload/massmail/...` (UI 장식 이미지는 제외) |
| `apply_links` | 스크러빙·언랩된 링크, ` \| ` 구분 |
| `body_chars` | 본문 텍스트 길이 |
| `body_text` | 본문 텍스트 (8000자 상한) |

샘플 한 행:

```
uid=9248  msgid=1871575337617834975  2026-07-24T15:07:23+09:00
[수정재발송] 서울대학교 김구포럼 특별세미나 <War and State-making> (연사: 주호정 교수(하버드대))
from=국제학연구소 <he0424@snu.ac.kr>   mailer=ThunderMail5.0
poster=letter2.snu.ac.kr/upload/massmail/1E1C1E51137AE0AEB67E7167CD02A4D9178485
links=https://forms.gle/ix3U4cj3E8NNz2gs9
body=1631자
```

### 개인정보 스크러빙 검증

CSV 전체 검사 결과 **모두 0건**: 수신자 아이디, 그 아이디의 base64 인코딩, `EMA=`, `reject_list`, `email=`.

### 파싱 가능성

행사 메일 본문이 이런 구조로 들어온다:

```
1. 일시
- 2026년 8월 6일(목) 15:00-17:00
2. 모집대상 ...
4. 신청 방법
- 2026년 8월 5일 (수) 15:00까지
```

기존 `EliceEventParserClient`의 프롬프트 큐(`일시` / `신청` / `까지`)와 그대로 맞는다.
포스터도 `letter2.snu.ac.kr/upload/massmail/...jpg`에 **익명 접근 200 (165KB 확인)** 이라
`OciUploadService`로 바로 넘길 수 있다.

---

## 알아낸 함정

수집기를 만들 때 그대로 재발할 것들이라 기록해둔다.

**1. Gmail의 헤더 검색은 토큰 매칭이라 절반을 조용히 누락시킨다.**
서버측 `HEADER X-Mailer "thundermail"` 검색이 **0건**을 반환한다.
`ThunderMail5.0`이 `thundermail5`로 토큰화되어 안 맞는 것인데, `bizenic_sender`는 맞는다.
그래서 **에러 없이 정보화본부 설문메일만 담기고 행사 메일 81통이 전부 빠진 결과**가 나온다.
→ 헤더만 배치로 받아 로컬에서 판정한다 (`fetch_mailers`).

**2. 신청 링크는 `href`가 아니라 본문 텍스트에 있다.**
"신청" 버튼은 letter2 추적 래퍼(`response.do?...&URL=#`)이고 목적지가 서버측에만 있다.
`href`만 보면 신청링크 없음이 18/30이었다.
→ 본문 평문 URL까지 걷으면 **3/30**으로 줄고, 회수되는 게 정확히 `forms.gle`,
`docs.google.com/forms`, `notion.site`, `extra.snu.ac.kr` 같은 실제 신청처다 (`collect_text_urls`).

**3. 제목에 RFC2047 인코딩 없이 8bit EUC-KR을 넣는 메일이 있다.**
정보화본부 발신 메일이 그렇다. `decode_header`만 쓰면 제목이 깨진다.
→ surrogateescape 바이트를 복원해 cp949로 디코딩하고, `ks_c_5601-1987` 같은
비표준 charset 라벨은 코덱 별칭을 등록해 처리한다 (`decode_header_value`, `decode_bytes`).

**4. `imaplib.capabilities`는 `bytes`가 아니라 `str` 튜플이다.**
`b"X-GM-EXT-1" in conn.capabilities`가 예외 없이 항상 False가 되어
Gmail 확장 감지가 조용히 실패한다.

---

## 다음 단계 — 프로덕션 설계에 넘길 항목

이 POC는 프로덕션 코드가 아니다. 실제 수집은 Kotlin 배치 모듈
(`hangsha/batch/src/main/kotlin/com/team1/hangsha/batch/crawler/`)에 기존 크롤러와 같은 패턴으로
들어가고, `CrawledProgramEvent`를 만들어 `EliceEventParserClient` → `EventSyncService`로 흘려보낸다.

설계에서 정해야 할 것:

- **중복 판정 키** — `EventSyncService.resolveApplyLink`가 extra.snu / snunow URL 패턴을
  하드코딩하고 있어 메일용 분기가 필요하다. `X-GM-MSGID` 또는 `Message-ID` 기반.
- **소스 간 중복** — 대량메일 30건 중 **4건이 본문에 `extra.snu.ac.kr` 링크를 포함**한다.
  그 링크를 뽑아 `applyLink`로 쓰면 기존 upsert 키와 일치해 **자동으로 같은 행사로 병합**된다.
  제목 유사도 매칭이 필요 없다.
- **행사/비행사 분류** — 설문조사, 시스템 점검, 채용 공고가 섞여 있다.
  현재 파이프라인에 없는 분류 단계가 앞단에 필요하다.
- **`category` 화이트리스트** — 제목 접두사가 대개 발신 부서지만
  `[수정재발송]`, `[재발송]`처럼 부서명이 아닌 값도 섞인다.
- **증분 수집** — `UIDVALIDITY` + 마지막 처리 UID 워터마크. `UIDVALIDITY`가 바뀌면 전체 재스캔.
- **인증 방식 전환** — 앱 비밀번호는 장기 유효 평문 자격증명이다. 운영에서는 OAuth2로
  스코프를 `gmail.readonly`로 좁히는 게 맞다. IMAP도 `AUTH=XOAUTH2`를 지원하므로
  **인증부만 바꾸면 파싱 로직은 그대로 재사용된다.**
- **정책 확인** — 학내 구성원 대상 공지를 외부 서비스에 재게시하는 것에 대해
  정보화본부 문의. 담당자 연락처 등 개인정보 마스킹도 함께 검토.
