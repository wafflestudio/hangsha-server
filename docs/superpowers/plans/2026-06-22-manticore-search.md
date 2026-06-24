# Manticore Search 도입 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SQL LIKE 기반 행사 검색을 kiwi 형태소 분석 + Manticore Search로 대체하고, Outbox 패턴으로 인덱스 동기화를 보장한다.

**Architecture:** kiwi Python sidecar가 형태소 분석을 담당한다. Spring 앱은 인덱싱/검색 전 kiwi에서 토크나이즈된 토큰 문자열을 Manticore RT table에 저장한다. EventSyncService는 Spring ApplicationEvent를 발행하고, hangsha의 EventSearchOutboxListener가 outbox 테이블에 기록한다. OutboxWorker는 **hangsha API 서버(`:hangsha` 모듈)에서 `@Scheduled`로 실행**되며, outbox 테이블을 폴링하여 Manticore를 동기화한다. batch 모듈에서 실행하지 않는다.

**Tech Stack:** Kotlin, Spring Boot 3.5.9, Spring Data JDBC, RestClient, Manticore Search JSON API, Python FastAPI + kiwipiepy, Jsoup, Testcontainers

## Global Constraints

- Manticore HTTP API (local): `http://localhost:19308`, (docker 내부): `http://manticore:9308`
- Kiwi service (local): `http://localhost:8090`, (docker 내부): `http://kiwi:8090`
- Manticore RT table 이름: `events_search`
- Outbox table 이름: `event_search_outbox` (Flyway V27)
- Content 전처리: Jsoup HTML 태그 제거 → kiwi 형태소 분석 → 공백 구분 토큰 문자열
- Title 전처리: kiwi 형태소 분석 → 공백 구분 토큰 문자열
- Manticore 문서 id = Event.id (MySQL PK)
- title 검색, content 검색 각각 별도 구현 (동시 검색 보류)
- Spring Boot 3.5.9 → RestClient 사용 (`spring-boot-starter-web`에 포함)
- kiwi 장애 시 원본 텍스트 fallback으로 검색 가용성 유지
- OutboxWorker는 `:hangsha` API 서버 모듈에서만 실행 (`@EnableScheduling` 추가 위치: `HangshaApplication.kt`), `:batch` 모듈에는 스케줄러 추가 금지

---

## 파일 구조 (전체 변경 목록)

### 신규 생성

```
kiwi-service/
  Dockerfile
  main.py
  requirements.txt

common/src/main/kotlin/com/team1/hangsha/search/   ← 인덱싱/서칭 코어
  ManticoreClient.kt
  KiwiTokenizerClient.kt
  ManticoreTableInitializer.kt
  ManticoreIndexService.kt
  ManticoreSearchService.kt

common/src/main/kotlin/com/team1/hangsha/search/outbox/  ← 크롤러+API 서버 공용
  EventSearchOutbox.kt
  EventSearchOutboxRepository.kt
  EventSearchOutboxListener.kt

hangsha/src/main/kotlin/com/team1/hangsha/search/  ← hangsha 전용 (API 서버에서만 실행)
  ManticoreReindexService.kt
  SearchAdminController.kt
  outbox/
    OutboxWorker.kt

hangsha/src/main/resources/db/migration/
  V27__create_table_event_search_outbox.sql

common/src/main/kotlin/com/team1/hangsha/event/
  EventSearchSyncEvent.kt
```

### 수정

```
hangsha/docker-compose.yml              ← kiwi 서비스 추가
common/build.gradle.kts                ← jsoup 의존성 추가
hangsha/src/main/resources/application.yml  ← manticore/kiwi URL 설정 추가
common/src/main/kotlin/com/team1/hangsha/event/service/EventSyncService.kt
  ← ApplicationEventPublisher 주입, publishEvent() 호출 추가
hangsha/src/main/kotlin/com/team1/hangsha/event/repository/EventQueryRepository.kt
  ← findVisibleByIds() 추가
hangsha/src/main/kotlin/com/team1/hangsha/event/service/EventService.kt
  ← searchTitle() Manticore로 대체, searchContent() 추가
hangsha/src/main/kotlin/com/team1/hangsha/event/controller/EventController.kt
  ← GET /search/content 엔드포인트 추가
hangsha/src/main/kotlin/com/team1/hangsha/HangshaApplication.kt
  ← @EnableScheduling 추가
```

---

### Task 1: Kiwi tokenizer sidecar + docker-compose 업데이트

**Files:**
- Create: `kiwi-service/Dockerfile`
- Create: `kiwi-service/main.py`
- Create: `kiwi-service/requirements.txt`
- Modify: `hangsha/docker-compose.yml`

**Interfaces:**
- Produces: `POST http://localhost:8090/tokenize` — `{"text": "한국어 텍스트"}` → `{"tokens": "한국 어 텍스트"}`
- Produces: `GET http://localhost:8090/health` → `{"status": "ok"}`

- [ ] **Step 1: kiwi-service 디렉토리 및 파일 생성**

`kiwi-service/requirements.txt`:
```
fastapi==0.115.0
uvicorn==0.32.0
kiwipiepy==0.20.0
```

`kiwi-service/main.py`:
```python
from fastapi import FastAPI
from pydantic import BaseModel
from kiwipiepy import Kiwi

app = FastAPI()
kiwi = Kiwi()

class TokenizeRequest(BaseModel):
    text: str

class TokenizeResponse(BaseModel):
    tokens: str

@app.post("/tokenize", response_model=TokenizeResponse)
def tokenize(req: TokenizeRequest):
    if not req.text.strip():
        return TokenizeResponse(tokens="")
    result = kiwi.tokenize(req.text)
    tokens = " ".join(t.form for t in result if t.form.strip())
    return TokenizeResponse(tokens=tokens)

@app.get("/health")
def health():
    return {"status": "ok"}
```

`kiwi-service/Dockerfile`:
```dockerfile
FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY main.py .
EXPOSE 8090
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8090"]
```

- [ ] **Step 2: docker-compose.yml에 kiwi 서비스 추가**

`hangsha/docker-compose.yml`의 manticore 서비스 다음에 추가:
```yaml
  kiwi:
    build:
      context: ../kiwi-service
    container_name: campus-kiwi
    ports:
      - "8090:8090"
    restart: unless-stopped
```

- [ ] **Step 3: kiwi 서비스 빌드 및 기동**

Run:
```bash
cd hangsha
docker compose build kiwi && docker compose up kiwi -d
```
Expected: `campus-kiwi  Up` 출력

- [ ] **Step 4: tokenize 엔드포인트 수동 테스트**

Run:
```bash
curl -s -X POST http://localhost:8090/tokenize \
  -H "Content-Type: application/json" \
  -d '{"text": "융합사업단을 지원하는 프로그램"}'
```
Expected (형태소 분리된 결과):
```json
{"tokens": "융합 사업 단 을 지원 하 는 프로그램"}
```

- [ ] **Step 5: Commit**

```bash
git add kiwi-service/ hangsha/docker-compose.yml
git commit -m "feat: add kiwi tokenizer sidecar"
```

---

### Task 2: ManticoreClient + KiwiTokenizerClient + 설정

**Files:**
- Create: `common/src/main/kotlin/com/team1/hangsha/search/ManticoreClient.kt`
- Create: `common/src/main/kotlin/com/team1/hangsha/search/KiwiTokenizerClient.kt`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Produces: `ManticoreClient.sql(query: String): Any` — SQL 실행
- Produces: `ManticoreClient.replace(index, id, doc)` — 문서 upsert
- Produces: `ManticoreClient.delete(index, id)` — 문서 삭제
- Produces: `ManticoreClient.search(requestBody)` — JSON 검색
- Produces: `KiwiTokenizerClient.tokenize(text: String): String` — 형태소 토크나이즈 (장애 시 원본 반환)

- [ ] **Step 1: application.yml에 설정 추가**

`src/main/resources/application.yml`의 `local` 프로파일 섹션(`spring.config.activate.on-profile: local`) 안에 추가:
```yaml
manticore:
  base-url: http://localhost:19308

kiwi:
  base-url: http://localhost:8090
```

- [ ] **Step 2: ManticoreClient 작성**

`common/src/main/kotlin/com/team1/hangsha/search/ManticoreClient.kt`:
```kotlin
package com.team1.hangsha.search

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Component
class ManticoreClient(
    @Value("\${manticore.base-url}") private val baseUrl: String,
    private val objectMapper: ObjectMapper,
) {
    private val client by lazy { RestClient.create(baseUrl) }

    fun sql(query: String): Any {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        return client.post()
            .uri("/sql?mode=raw")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("query=$encoded")
            .retrieve()
            .body(Any::class.java)!!
    }

    @Suppress("UNCHECKED_CAST")
    fun replace(index: String, id: Long, doc: Map<String, Any>): Map<String, Any> {
        val body = mapOf("index" to index, "id" to id, "doc" to doc)
        return client.post()
            .uri("/json/replace")
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsString(body))
            .retrieve()
            .body(Map::class.java)!! as Map<String, Any>
    }

    @Suppress("UNCHECKED_CAST")
    fun delete(index: String, id: Long): Map<String, Any> {
        val body = mapOf("index" to index, "id" to id)
        return client.post()
            .uri("/json/delete")
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsString(body))
            .retrieve()
            .body(Map::class.java)!! as Map<String, Any>
    }

    @Suppress("UNCHECKED_CAST")
    fun search(requestBody: Map<String, Any>): Map<String, Any> {
        return client.post()
            .uri("/json/search")
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsString(requestBody))
            .retrieve()
            .body(Map::class.java)!! as Map<String, Any>
    }
}
```

- [ ] **Step 3: KiwiTokenizerClient 작성**

`common/src/main/kotlin/com/team1/hangsha/search/KiwiTokenizerClient.kt`:
```kotlin
package com.team1.hangsha.search

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class KiwiTokenizerClient(
    @Value("\${kiwi.base-url}") private val baseUrl: String,
    private val objectMapper: ObjectMapper,
) {
    private val client by lazy { RestClient.create(baseUrl) }
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("UNCHECKED_CAST")
    fun tokenize(text: String): String {
        if (text.isBlank()) return ""
        return try {
            val body = mapOf("text" to text)
            val response = client.post()
                .uri("/tokenize")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .body(Map::class.java)!!
            (response as Map<String, Any>)["tokens"] as? String ?: text
        } catch (e: Exception) {
            log.warn("kiwi service unavailable, falling back to raw text: ${e.message}")
            text
        }
    }
}
```

- [ ] **Step 4: 앱 빌드 확인**

Run:
```bash
./gradlew :compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add common/src/main/kotlin/com/team1/hangsha/search/ManticoreClient.kt \
        common/src/main/kotlin/com/team1/hangsha/search/KiwiTokenizerClient.kt \
        src/main/resources/application.yml
git commit -m "feat: add ManticoreClient and KiwiTokenizerClient"
```

---

### Task 3: Manticore RT 테이블 초기화

**Files:**
- Create: `common/src/main/kotlin/com/team1/hangsha/search/ManticoreTableInitializer.kt`

**Interfaces:**
- Consumes: `ManticoreClient.sql(query)`
- Produces: 앱 시작 시 `events_search` 테이블이 없으면 자동 생성

- [ ] **Step 1: ManticoreTableInitializer 작성**

`common/src/main/kotlin/com/team1/hangsha/search/ManticoreTableInitializer.kt`:
```kotlin
package com.team1.hangsha.search

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ManticoreTableInitializer(
    private val manticoreClient: ManticoreClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun init() {
        try {
            manticoreClient.sql(
                """
                CREATE TABLE IF NOT EXISTS events_search(
                    title TEXT,
                    content TEXT
                )
                """.trimIndent()
            )
            log.info("Manticore events_search table initialized")
        } catch (e: Exception) {
            log.warn("Manticore table init failed (will retry on next event sync): ${e.message}")
        }
    }
}
```

- [ ] **Step 2: 앱 기동 후 테이블 확인**

Run (앱 기동 후):
```bash
mysql -h 127.0.0.1 -P 19306 -e "SHOW TABLES;"
```
Expected: `events_search` 포함 결과 출력

- [ ] **Step 3: Commit**

```bash
git add common/src/main/kotlin/com/team1/hangsha/search/ManticoreTableInitializer.kt
git commit -m "feat: initialize Manticore events_search RT table on startup"
```

---

### Task 4: Event search outbox 테이블 + 모델 + 리포지토리

**Files:**
- Create: `src/main/resources/db/migration/V27__create_table_event_search_outbox.sql`
- Create: `src/main/kotlin/com/team1/hangsha/search/outbox/EventSearchOutbox.kt`
- Create: `src/main/kotlin/com/team1/hangsha/search/outbox/EventSearchOutboxRepository.kt`

**Interfaces:**
- Produces: `EventSearchOutboxRepository.findPendingOrderedById(limit: Int): List<EventSearchOutbox>`
- Produces: `EventSearchOutboxRepository.updateStatus(id, status, processedAt)`

- [ ] **Step 1: Flyway 마이그레이션 파일 작성**

`src/main/resources/db/migration/V27__create_table_event_search_outbox.sql`:
```sql
CREATE TABLE event_search_outbox (
    id         BIGINT NOT NULL AUTO_INCREMENT,
    event_id   BIGINT NOT NULL,
    operation  ENUM('UPSERT', 'DELETE') NOT NULL,
    status     ENUM('PENDING', 'DONE', 'FAILED') NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    processed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    KEY idx_event_search_outbox_status_id (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- [ ] **Step 2: EventSearchOutbox 엔티티 작성**

`src/main/kotlin/com/team1/hangsha/search/outbox/EventSearchOutbox.kt`:
```kotlin
package com.team1.hangsha.search.outbox

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("event_search_outbox")
data class EventSearchOutbox(
    @Id
    val id: Long? = null,
    val eventId: Long,
    val operation: Operation,
    val status: Status = Status.PENDING,
    @CreatedDate
    val createdAt: Instant? = null,
    val processedAt: Instant? = null,
) {
    enum class Operation { UPSERT, DELETE }
    enum class Status { PENDING, DONE, FAILED }
}
```

- [ ] **Step 3: EventSearchOutboxRepository 작성**

`src/main/kotlin/com/team1/hangsha/search/outbox/EventSearchOutboxRepository.kt`:
```kotlin
package com.team1.hangsha.search.outbox

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param
import java.time.Instant

interface EventSearchOutboxRepository : CrudRepository<EventSearchOutbox, Long> {

    @Query("SELECT * FROM event_search_outbox WHERE status = 'PENDING' ORDER BY id ASC LIMIT :limit")
    fun findPendingOrderedById(@Param("limit") limit: Int): List<EventSearchOutbox>

    @Modifying
    @Query("UPDATE event_search_outbox SET status = :status, processed_at = :processedAt WHERE id = :id")
    fun updateStatus(
        @Param("id") id: Long,
        @Param("status") status: String,
        @Param("processedAt") processedAt: Instant,
    ): Int
}
```

- [ ] **Step 4: 앱 기동 후 테이블 생성 확인**

Run:
```bash
./gradlew bootRun
```
Run (다른 터미널):
```bash
mysql -h 127.0.0.1 -P 3307 -u user -ppassword campus_db -e "DESCRIBE event_search_outbox;"
```
Expected: id, event_id, operation, status, created_at, processed_at 컬럼 포함

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V27__create_table_event_search_outbox.sql \
        src/main/kotlin/com/team1/hangsha/search/outbox/
git commit -m "feat: add event_search_outbox table and repository"
```

---

### Task 5: EventSearchOutboxWriter 헬퍼 생성

EventSyncService와 무관하게 common에 outbox 등록 헬퍼만 만든다.
호출 지점(EventSyncService, 크롤러 등)에서 주입해 사용하는 것은 각 호출자의 책임이다.

**Files:**
- Create: `common/src/main/kotlin/com/team1/hangsha/search/outbox/EventSearchOutboxWriter.kt`

**Interfaces:**
- Consumes: `EventSearchOutboxRepository`
- Produces: `EventSearchOutboxWriter.upsert(eventId: Long)`
- Produces: `EventSearchOutboxWriter.delete(eventId: Long)`

- [ ] **Step 1: EventSearchOutboxWriter 작성**

`common/src/main/kotlin/com/team1/hangsha/search/outbox/EventSearchOutboxWriter.kt`:
```kotlin
package com.team1.hangsha.search.outbox

import org.springframework.stereotype.Component

@Component
class EventSearchOutboxWriter(
    private val outboxRepository: EventSearchOutboxRepository,
) {
    fun upsert(eventId: Long) {
        outboxRepository.save(
            EventSearchOutbox(eventId = eventId, operation = EventSearchOutbox.Operation.UPSERT)
        )
    }

    fun delete(eventId: Long) {
        outboxRepository.save(
            EventSearchOutbox(eventId = eventId, operation = EventSearchOutbox.Operation.DELETE)
        )
    }
}
```

- [ ] **Step 2: 빌드 확인**

Run:
```bash
./gradlew :common:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add common/src/main/kotlin/com/team1/hangsha/search/outbox/EventSearchOutboxWriter.kt
git commit -m "feat: add EventSearchOutboxWriter helper in common"
```

---

### Task 6: ManticoreIndexService (인덱싱/삭제)

**Files:**
- Create: `common/src/main/kotlin/com/team1/hangsha/search/ManticoreIndexService.kt`
- Modify: `common/build.gradle.kts` (jsoup 추가)

**Interfaces:**
- Consumes: `ManticoreClient`, `KiwiTokenizerClient`
- Produces: `ManticoreIndexService.indexEvent(event: Event)` — kiwi 토크나이즈 후 Manticore에 upsert
- Produces: `ManticoreIndexService.deleteEvent(eventId: Long)` — Manticore에서 삭제

- [ ] **Step 1: common build.gradle.kts에 jsoup 추가**

`common/build.gradle.kts`의 `dependencies` 블록에 추가:
```kotlin
implementation("org.jsoup:jsoup:1.17.2")
```

- [ ] **Step 2: ManticoreIndexService 작성**

`common/src/main/kotlin/com/team1/hangsha/search/ManticoreIndexService.kt`:
```kotlin
package com.team1.hangsha.search

import com.team1.hangsha.event.model.Event
import org.jsoup.Jsoup
import org.springframework.stereotype.Service

@Service
class ManticoreIndexService(
    private val manticoreClient: ManticoreClient,
    private val kiwiTokenizerClient: KiwiTokenizerClient,
) {
    private val indexName = "events_search"

    fun indexEvent(event: Event) {
        val tokenizedTitle = kiwiTokenizerClient.tokenize(event.title)
        val rawContent = event.mainContentHtml?.let { stripHtml(it) } ?: ""
        val tokenizedContent = if (rawContent.isBlank()) "" else kiwiTokenizerClient.tokenize(rawContent)

        manticoreClient.upsert(
            index = indexName,
            id = requireNotNull(event.id) { "Event id must not be null" },
            doc = mapOf(
                "title" to tokenizedTitle,
                "content" to tokenizedContent,
            )
        )
    }

    fun deleteEvent(eventId: Long) {
        manticoreClient.delete(index = indexName, id = eventId)
    }

    private fun stripHtml(html: String): String = Jsoup.parse(html).text()
}
```

- [ ] **Step 3: 수동 테스트 — Manticore 인덱스 확인**

앱 기동 상태에서 (OutboxWorker는 다음 Task에서 구현):
ManticoreIndexService를 직접 호출하는 작은 테스트 or 다음 Task 완성 후 통합 확인

Run (SQL로 직접 확인):
```bash
mysql -h 127.0.0.1 -P 19306 \
  -e "SELECT id, title FROM events_search LIMIT 5;"
```

- [ ] **Step 4: Commit**

```bash
git add common/build.gradle.kts \
        common/src/main/kotlin/com/team1/hangsha/search/ManticoreIndexService.kt
git commit -m "feat: add ManticoreIndexService with kiwi tokenization and jsoup HTML stripping"
```

---

### Task 7: OutboxWorker (스케줄 폴링 + Manticore 동기화)

**Files:**
- Create: `src/main/kotlin/com/team1/hangsha/search/outbox/OutboxWorker.kt`
- Modify: `src/main/kotlin/com/team1/hangsha/HangshaApplication.kt`

**Interfaces:**
- Consumes: `EventSearchOutboxRepository.findPendingOrderedById(100)`
- Consumes: `ManticoreIndexService.indexEvent()` / `deleteEvent()`
- Consumes: `EventRepository.findVisibleById()`
- Produces: outbox 레코드를 DONE 또는 FAILED로 업데이트

- [ ] **Step 1: @EnableScheduling 추가**

`src/main/kotlin/com/team1/hangsha/HangshaApplication.kt`:
```kotlin
package com.team1.hangsha

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class HangshaApplication

fun main(args: Array<String>) {
    runApplication<HangshaApplication>(*args)
}
```

- [ ] **Step 2: OutboxWorker 작성**

`src/main/kotlin/com/team1/hangsha/search/outbox/OutboxWorker.kt`:
```kotlin
package com.team1.hangsha.search.outbox

import com.team1.hangsha.event.repository.EventRepository
import com.team1.hangsha.search.ManticoreIndexService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class OutboxWorker(
    private val outboxRepository: EventSearchOutboxRepository,
    private val manticoreIndexService: ManticoreIndexService,
    private val eventRepository: EventRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 5000)
    fun processOutbox() {
        val pending = outboxRepository.findPendingOrderedById(100)
        if (pending.isEmpty()) return

        for (entry in pending) {
            val entryId = requireNotNull(entry.id)
            try {
                when (entry.operation) {
                    EventSearchOutbox.Operation.UPSERT -> {
                        val event = eventRepository.findVisibleById(entry.eventId)
                        if (event != null) {
                            manticoreIndexService.indexEvent(event)
                        } else {
                            manticoreIndexService.deleteEvent(entry.eventId)
                        }
                    }
                    EventSearchOutbox.Operation.DELETE -> {
                        manticoreIndexService.deleteEvent(entry.eventId)
                    }
                }
                outboxRepository.updateStatus(entryId, "DONE", Instant.now())
                log.debug("Processed outbox entry id={} eventId={} op={}", entryId, entry.eventId, entry.operation)
            } catch (e: Exception) {
                outboxRepository.updateStatus(entryId, "FAILED", Instant.now())
                log.error("Failed to process outbox entry id={}: {}", entryId, e.message)
            }
        }
    }
}
```

- [ ] **Step 3: 통합 흐름 확인**

앱 기동 후, admin API로 이벤트를 하나 생성하고 5초 후 상태 확인:

```bash
# 이벤트 생성 (outbox에 PENDING 레코드 생성됨)
curl -s -X POST http://localhost:8080/api/v1/admin/events \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <admin-token>" \
  -d '{"title": "융합사업단 지원 프로그램 테스트"}'

# 5초 후 outbox 상태 확인
mysql -h 127.0.0.1 -P 3307 -u user -ppassword campus_db \
  -e "SELECT id, event_id, operation, status FROM event_search_outbox ORDER BY id DESC LIMIT 5;"
```
Expected: status=DONE

```bash
# Manticore 인덱스 확인
mysql -h 127.0.0.1 -P 19306 \
  -e "SELECT id, title FROM events_search LIMIT 5;"
```
Expected: 방금 생성된 이벤트의 토크나이즈된 title 포함

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/team1/hangsha/HangshaApplication.kt \
        src/main/kotlin/com/team1/hangsha/search/outbox/OutboxWorker.kt
git commit -m "feat: add OutboxWorker polling Manticore sync every 5s"
```

---

### Task 8: ManticoreSearchService (title / content 검색)

**Files:**
- Create: `common/src/main/kotlin/com/team1/hangsha/search/ManticoreSearchService.kt`

**Interfaces:**
- Consumes: `ManticoreClient.search()`, `KiwiTokenizerClient.tokenize()`
- Produces: `ManticoreSearchService.searchByTitle(query, page, size): SearchResult`
- Produces: `ManticoreSearchService.searchByContent(query, page, size): SearchResult`
- Produces: `data class SearchResult(val eventIds: List<Long>, val total: Int)`

- [ ] **Step 1: ManticoreSearchService 작성**

`common/src/main/kotlin/com/team1/hangsha/search/ManticoreSearchService.kt`:
```kotlin
package com.team1.hangsha.search

import org.springframework.stereotype.Service

@Service
class ManticoreSearchService(
    private val manticoreClient: ManticoreClient,
    private val kiwiTokenizerClient: KiwiTokenizerClient,
) {
    private val indexName = "events_search"

    data class SearchResult(val eventIds: List<Long>, val total: Int)

    fun searchByTitle(query: String, page: Int, size: Int): SearchResult {
        val tokenized = kiwiTokenizerClient.tokenize(query)
        return doSearch(field = "title", tokenizedQuery = tokenized, page = page, size = size)
    }

    fun searchByContent(query: String, page: Int, size: Int): SearchResult {
        val tokenized = kiwiTokenizerClient.tokenize(query)
        return doSearch(field = "content", tokenizedQuery = tokenized, page = page, size = size)
    }

    @Suppress("UNCHECKED_CAST")
    private fun doSearch(field: String, tokenizedQuery: String, page: Int, size: Int): SearchResult {
        val offset = (page - 1) * size
        val requestBody = mapOf(
            "index" to indexName,
            "query" to mapOf("match" to mapOf(field to tokenizedQuery)),
            "limit" to size,
            "offset" to offset,
        )
        val response = manticoreClient.search(requestBody)
        val hits = response["hits"] as? Map<String, Any> ?: return SearchResult(emptyList(), 0)
        val total = (hits["total"] as? Number)?.toInt() ?: 0
        val hitList = hits["hits"] as? List<Map<String, Any>> ?: emptyList()
        val ids = hitList.mapNotNull { (it["_id"] as? Number)?.toLong() }
        return SearchResult(eventIds = ids, total = total)
    }
}
```

- [ ] **Step 2: 수동 테스트 — 검색 동작 확인**

앱 기동 상태 + Manticore에 데이터 있는 상태에서 직접 HTTP 호출 (다음 Task에서 API 연결 후 전체 확인):

```bash
curl -s -X POST http://localhost:19308/json/search \
  -H "Content-Type: application/json" \
  -d '{"index":"events_search","query":{"match":{"title":"사업"}},"limit":5}'
```
Expected: 관련 이벤트 id 포함 결과

- [ ] **Step 3: Commit**

```bash
git add common/src/main/kotlin/com/team1/hangsha/search/ManticoreSearchService.kt
git commit -m "feat: add ManticoreSearchService for title and content search"
```

---

### Task 9: EventService + EventController 업데이트 (SQL 검색 → Manticore)

**Files:**
- Modify: `src/main/kotlin/com/team1/hangsha/event/repository/EventQueryRepository.kt`
- Modify: `src/main/kotlin/com/team1/hangsha/event/service/EventService.kt`
- Modify: `src/main/kotlin/com/team1/hangsha/event/controller/EventController.kt`

**Interfaces:**
- Consumes: `ManticoreSearchService.searchByTitle()`, `ManticoreSearchService.searchByContent()`
- Produces: `EventQueryRepository.findVisibleByIds(ids: List<Long>): List<Event>`
- Produces: `EventService.searchContent(query, page, size, userId): TitleSearchEventResponse`
- Produces: `GET /api/v1/events/search/content?query=&page=&size=`

- [ ] **Step 1: EventQueryRepository에 findVisibleByIds 추가**

`src/main/kotlin/com/team1/hangsha/event/repository/EventQueryRepository.kt`에 메서드 추가:
```kotlin
fun findVisibleByIds(ids: List<Long>): List<Event> {
    if (ids.isEmpty()) return emptyList()
    val sql = "SELECT e.* FROM events e WHERE e.id IN (:ids) AND e.admin_deleted = false"
    return jdbc.query(sql, mapOf("ids" to ids)) { rs, _ -> rs.toEvent() }
}
```

- [ ] **Step 2: EventService — searchTitle Manticore로 교체 + searchContent 추가**

`src/main/kotlin/com/team1/hangsha/event/service/EventService.kt`에서:

1. 생성자에 `ManticoreSearchService` 추가:
```kotlin
@Service
class EventService(
    private val eventRepository: EventRepository,
    private val eventQueryRepository: EventQueryRepository,
    private val userInterestCategoryRepository: UserInterestCategoryRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val manticoreSearchService: ManticoreSearchService,
)
```

2. 기존 `searchTitle()` 메서드를 아래로 교체:
```kotlin
fun searchTitle(
    query: String,
    page: Int,
    size: Int,
    userId: Long?,
): TitleSearchEventResponse {
    val q = query.trim()
    if (q.isEmpty()) throw DomainException(ErrorCode.INVALID_REQUEST, "query는 비어있을 수 없습니다")
    val safePage = max(1, page)
    val safeSize = max(1, size)
    val searchResult = manticoreSearchService.searchByTitle(q, safePage, safeSize)
    return buildSearchResponse(searchResult, safePage, safeSize, userId)
}
```

3. `searchContent()` 추가:
```kotlin
fun searchContent(
    query: String,
    page: Int,
    size: Int,
    userId: Long?,
): TitleSearchEventResponse {
    val q = query.trim()
    if (q.isEmpty()) throw DomainException(ErrorCode.INVALID_REQUEST, "query는 비어있을 수 없습니다")
    val safePage = max(1, page)
    val safeSize = max(1, size)
    val searchResult = manticoreSearchService.searchByContent(q, safePage, safeSize)
    return buildSearchResponse(searchResult, safePage, safeSize, userId)
}
```

4. `buildSearchResponse()` private 함수 추가:
```kotlin
private fun buildSearchResponse(
    searchResult: ManticoreSearchService.SearchResult,
    page: Int,
    size: Int,
    userId: Long?,
): TitleSearchEventResponse {
    val events = eventQueryRepository.findVisibleByIds(searchResult.eventIds)
    val eventMap = events.associateBy { it.id!! }
    val orderedEvents = searchResult.eventIds.mapNotNull { eventMap[it] }

    val interestPriorityByCategoryId = loadInterestMap(userId)
    val auth = userId != null
    val bookmarkedIds: Set<Long> =
        if (auth) bookmarkRepository.findBookmarkedEventIdsIn(
            userId, orderedEvents.mapNotNull { it.id }
        ) else emptySet()

    val items = orderedEvents.map { e ->
        val matchedPriority = e.matchedInterestPriority(interestPriorityByCategoryId)
        val isBookmarked = if (auth) bookmarkedIds.contains(requireNotNull(e.id)) else null
        e.toDto(auth, matchedPriority, isBookmarked)
    }

    return TitleSearchEventResponse(
        page = page,
        size = size,
        total = searchResult.total,
        items = items,
    )
}
```

5. 기존 `searchTitle()`에서 사용하던 아래 두 메서드는 더 이상 호출되지 않으므로 `EventQueryRepository`에서 제거:
   - `countByTitleContains()`
   - `findByTitleContainsPaged()`

- [ ] **Step 3: EventController에 /search/content 엔드포인트 추가**

`src/main/kotlin/com/team1/hangsha/event/controller/EventController.kt`에 추가:
```kotlin
@GetMapping("/search/content")
fun searchContent(
    @Parameter(hidden = true) @LoggedInUser user: User?,
    @RequestParam("query") query: String,
    @RequestParam("page", defaultValue = "1") page: Int,
    @RequestParam("size", defaultValue = "20") size: Int,
): TitleSearchEventResponse =
    eventService.searchContent(
        query = query,
        page = page,
        size = size,
        userId = user?.id,
    )
```

- [ ] **Step 4: SecurityConfig에 /search/content 경로 허용**

`src/main/kotlin/com/team1/hangsha/config/SecurityConfig.kt`의 `permitAll()` 블록에 추가:
```kotlin
"/api/v1/events/search/title",
"/api/v1/events/search/content",
```

- [ ] **Step 5: 빌드 및 검색 엔드포인트 테스트**

Run:
```bash
./gradlew :compileKotlin
```
Expected: BUILD SUCCESSFUL

Run (앱 기동 후):
```bash
curl -s "http://localhost:8080/api/v1/events/search/title?query=사업"
curl -s "http://localhost:8080/api/v1/events/search/content?query=프로그램"
```
Expected: `{"page":1,"size":20,"total":N,"items":[...]}` 형식 응답

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/team1/hangsha/event/repository/EventQueryRepository.kt \
        src/main/kotlin/com/team1/hangsha/event/service/EventService.kt \
        src/main/kotlin/com/team1/hangsha/event/controller/EventController.kt \
        src/main/kotlin/com/team1/hangsha/config/SecurityConfig.kt
git commit -m "feat: replace SQL title search with Manticore and add content search endpoint"
```

---

### Task 10: 배치 보상 프로세스 엔드포인트 (전체 재인덱싱)

**Files:**
- Create: `src/main/kotlin/com/team1/hangsha/search/ManticoreReindexService.kt`
- Create: `src/main/kotlin/com/team1/hangsha/search/SearchAdminController.kt`

**Interfaces:**
- Consumes: `EventRepository.findAll()`, `ManticoreIndexService.indexEvent()`
- Produces: `POST /api/v1/admin/search/reindex` → `{"ok": true, "indexed": N}`

- [ ] **Step 1: ManticoreReindexService 작성**

`src/main/kotlin/com/team1/hangsha/search/ManticoreReindexService.kt`:
```kotlin
package com.team1.hangsha.search

import com.team1.hangsha.event.repository.EventRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Service
class ManticoreReindexService(
    @Value("\${manticore.base-url}") private val baseUrl: String,
    private val eventRepository: EventRepository,
    private val manticoreIndexService: ManticoreIndexService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client by lazy { RestClient.create(baseUrl) }

    fun reindexAll(): Map<String, Any> {
        // sql()은 ManticoreClient에 없으므로 직접 HTTP 호출
        val sql = "TRUNCATE TABLE events_search"
        val encoded = URLEncoder.encode(sql, StandardCharsets.UTF_8)
        client.post()
            .uri("/sql?mode=raw")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("query=$encoded")
            .retrieve()
            .toBodilessEntity()
        log.info("Truncated events_search table for full reindex")

        var indexed = 0
        var failed = 0
        eventRepository.findAll()
            .filter { !it.adminDeleted }
            .forEach { event ->
                runCatching { manticoreIndexService.indexEvent(event) }
                    .onSuccess { indexed++ }
                    .onFailure { e ->
                        failed++
                        log.error("Failed to index eventId={}: {}", event.id, e.message)
                    }
            }

        log.info("Reindex complete: indexed={}, failed={}", indexed, failed)
        return mapOf("ok" to true, "indexed" to indexed, "failed" to failed)
    }
}
```

- [ ] **Step 2: SearchAdminController 작성**

`src/main/kotlin/com/team1/hangsha/search/SearchAdminController.kt`:
```kotlin
package com.team1.hangsha.search

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/search")
class SearchAdminController(
    private val manticoreReindexService: ManticoreReindexService,
) {
    @PostMapping("/reindex")
    fun reindex(): Map<String, Any> = manticoreReindexService.reindexAll()
}
```

- [ ] **Step 3: 재인덱싱 엔드포인트 테스트**

Run:
```bash
curl -s -X POST http://localhost:8080/api/v1/admin/search/reindex \
  -H "Authorization: Bearer <admin-token>"
```
Expected:
```json
{"ok": true, "indexed": 100, "failed": 0}
```

재인덱싱 후 검색이 정상 동작하는지 확인:
```bash
curl -s "http://localhost:8080/api/v1/events/search/title?query=사업"
```
Expected: Manticore 검색 결과 반환

- [ ] **Step 4: 최종 빌드 확인**

Run:
```bash
./gradlew :build
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/team1/hangsha/search/ManticoreReindexService.kt \
        src/main/kotlin/com/team1/hangsha/search/SearchAdminController.kt
git commit -m "feat: add /admin/search/reindex compensation endpoint"
```

---

### Task 11: 검색 고도화 (보류)

> **현재 구현**: kiwi 형태소 분석 → 공백 분리 토큰 → Manticore 단순 match
> **참고 문서**: `docs/manticore-plan.md`

아래 항목은 기본 검색이 안정화된 후 진행한다.

- [ ] 테이블 스키마 변경: `title` / `content` → `title_tokens` / `content_tokens` / `title_raw` / `content_raw` (4필드)
- [ ] `min_infix_len=2`, `rt_mem_limit=64M` 옵션 추가
- [ ] kiwi 사이드카: 특수문자 전처리 + NNG/NNP/VV 등 품사 필터링 적용
- [ ] 검색 쿼리: `bool/should` OR (kiwi tokens + raw 두 경로 합산) + infix 래핑 (`*token*`)
- [ ] `CALL SUGGEST` 기반 철자 교정 (편집 거리 1, 접미사 치환만 적용)
- [ ] Recall/Precision 튜닝: 토큰 수 기반 동적 quorum 연산자

