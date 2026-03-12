# Hotel Reservation - 동시성 제어 학습 프로젝트

호텔 예약 시스템에서 **동일한 객실에 대해 동시에 예약 요청이 들어올 때** 중복 예약을 방지하는 다양한 동시성 제어 전략을 구현하고 비교하는 프로젝트입니다.

특히 **데이터가 없는 상태에서 동시에 INSERT가 발생하는 케이스**에 초점을 맞추고 있습니다. 재고 차감처럼 이미 존재하는 row를 잠그는 것과 달리, 아직 존재하지 않는 예약을 INSERT할 때 발생하는 **Phantom Read 문제**를 5가지 서로 다른 전략으로 해결합니다.

## 기술 스택

| 분류 | 기술 |
|---|---|
| Language | Kotlin 2.2, Java 21 |
| Framework | Spring Boot 4.0, Spring Data JPA |
| Database | MySQL 8.0 (Docker) |
| Cache / Lock | Redis 7 (Docker) + Redisson 3.45 |
| Test | JUnit 5, Testcontainers 2.0 |
| Build | Gradle (Kotlin DSL) |

## 사전 준비

- **Java 21** 이상
- **Docker** (MySQL, Redis 컨테이너 실행용)

> 테스트 실행 시에는 Docker만 있으면 됩니다. Testcontainers가 자동으로 MySQL, Redis 컨테이너를 생성합니다.

## 시작하기

### 테스트 실행

```bash
./gradlew test
```

Testcontainers가 MySQL, Redis를 자동으로 띄우고 테스트 후 정리합니다. 별도 설정이 필요 없습니다.

### 로컬 개발 환경

```bash
# 1. MySQL, Redis 컨테이너 실행
docker compose up -d

# 2. 애플리케이션 실행
./gradlew bootRun
```

Spring Boot Docker Compose가 `docker-compose.yml`을 감지하여 자동으로 연결합니다.

## 프로젝트 구조

```
src/main/kotlin/server/hotelreservation/
├── HotelReservationApplication.kt          # 애플리케이션 진입점
├── config/
│   ├── DataSourceConfig.kt                 # Named Lock 전용 DataSource (커넥션 분리)
│   └── RedissonConfig.kt                   # Redisson 클라이언트 설정
├── domain/
│   ├── Room.kt                             # 객실 엔티티 (Pessimistic Lock 대상)
│   ├── Reservation.kt                      # 예약 엔티티
│   └── RoomDate.kt                         # 날짜별 예약 현황 (Unique Constraint용)
├── dto/
│   └── ReservationRequest.kt               # 예약 요청 DTO + 날짜 유효성 검증
├── repository/
│   ├── RoomRepository.kt                   # Room 조회 + SELECT FOR UPDATE 쿼리
│   ├── ReservationRepository.kt            # 날짜 겹침 검사 쿼리 (EXISTS)
│   └── RoomDateRepository.kt               # RoomDate CRUD
└── service/
    ├── ReservationService.kt               # Pessimistic Lock / Unique Constraint / 이중 방어
    ├── ReservationNamedLockService.kt      # Named Lock 전략 (MySQL GET_LOCK)
    └── ReservationDistributedLockService.kt # 분산 락 전략 (Redis + Redisson)
```

## 동시성 제어 전략

### 문제 상황

```
Thread A: SELECT 예약 존재? → 없음 → INSERT (예약 생성)
Thread B: SELECT 예약 존재? → 없음 → INSERT (예약 생성)  ← 중복!
```

존재하지 않는 row는 잠글 수 없으므로, 두 스레드 모두 "없다"는 답을 받고 INSERT를 실행합니다.

---

### 1. Pessimistic Lock (비관적 락)

Room 테이블의 row에 `SELECT ... FOR UPDATE`를 걸어 동시 접근을 차단합니다.

```
Thread A: SELECT room FOR UPDATE → 락 획득 → 겹침 체크 → INSERT → COMMIT → 락 해제
Thread B: SELECT room FOR UPDATE → 대기 ──────────────────────────→ 락 획득 → 겹침 체크 → 실패
```

**핵심**: Reservation이 아닌 **Room을 잠근다.** 예약은 아직 존재하지 않아 잠글 수 없지만, Room row는 항상 존재하므로 이를 잠가서 "이 객실에 대한 예약 작업 중"임을 표현합니다.

```kotlin
// Room row에 배타적 락을 건다
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT r FROM Room r WHERE r.roomNumber = :roomNumber")
fun findByRoomNumberWithLock(roomNumber: Int): Room?
```

**한계:**
- 트랜잭션 범위 동안 락을 보유하므로, 트랜잭션이 길어지면 처리량 저하
- 여러 Room을 동시에 잠글 경우 잠금 순서 불일치 시 데드락 가능

---

### 2. Unique Constraint (유니크 제약조건)

애플리케이션 레벨의 락 대신 **DB 유니크 제약조건**을 동시성 제어 도구로 활용합니다. 예약 날짜 범위를 개별 날짜 row로 분해하여 `room_date` 테이블에 INSERT하고, `(room_id, reserved_date)` 유니크 제약이 중복을 자동으로 방지합니다.

```
5/1~5/3 예약 → room_date에 (101, 5/1), (101, 5/2) INSERT → 성공
5/2~5/4 예약 → room_date에 (101, 5/2) INSERT 시도 → 유니크 위반 → 롤백
```

**`flush()`를 명시적으로 호출하는 이유:** JPA는 기본적으로 트랜잭션 커밋 시점에 SQL을 실행합니다(쓰기 지연). `flush()` 없이는 유니크 위반 예외가 서비스 메서드 밖에서 발생하여 `try-catch`로 잡을 수 없습니다.

```kotlin
private fun saveRoomDates(room: Room, reservation: Reservation) {
    try {
        val roomDates = reservation.dateRange().map { date ->
            RoomDate(room = room, reservedDate = date, reservation = reservation)
        }
        roomDateRepository.saveAll(roomDates)
        roomDateRepository.flush()  // 즉시 INSERT 실행 → 유니크 위반을 여기서 catch
    } catch (e: DataIntegrityViolationException) {
        throw IllegalStateException("해당 날짜에 이미 예약된 객실입니다.")
    }
}
```

**한계:**
- `room_date` 추가 테이블 필요 (장기 숙박 시 다수의 row 생성)
- 예외 기반 흐름 제어 — 중복 시도가 빈번하면 스택 트레이스 생성 오버헤드
- 어떤 날짜에서 충돌했는지 파악하기 어려움

---

### 3. 이중 방어 (Pessimistic Lock + Unique Constraint)

Pessimistic Lock으로 1차 방어, Unique Constraint로 2차 방어합니다. 벨트와 멜빵을 동시에 하는 전략입니다.

```kotlin
@Transactional
fun reserveWithDoubleGuard(request: ReservationRequest): Reservation {
    val room = roomRepository.findByRoomNumberWithLock(request.roomNumber)  // 1차: 비관적 락
        ?: throw NoSuchElementException("존재하지 않는 객실입니다.")

    validateNotOverlapping(room, request)
    val reservation = createReservation(room, request)
    saveRoomDates(room, reservation)  // 2차: 유니크 제약조건
    return reservation
}
```

**왜 이중으로 방어하는가:** Pessimistic Lock은 코드 레벨의 보호 장치이므로 리팩토링이나 새로운 예약 경로 추가 시 락 호출을 빠뜨릴 수 있습니다. DB 유니크 제약조건은 데이터 레벨의 보호 장치이므로 코드가 어떻게 변경되든 중복 삽입을 물리적으로 차단합니다.

---

### 4. Named Lock (MySQL GET_LOCK)

MySQL의 User-Level Lock을 사용합니다. 테이블/row가 아닌 **이름(문자열) 기반**으로 락을 잡습니다.

```
락 전용 커넥션:   GET_LOCK('ROOM_101') ─────────────── RELEASE_LOCK('ROOM_101')
비즈니스 커넥션:            @Transactional { 겹침 체크 → INSERT → COMMIT }
```

**커넥션 분리가 필수인 이유:** Named Lock은 커넥션에 바인딩됩니다. 비즈니스 로직과 같은 커넥션을 사용하면 `@Transactional` 종료 시 커넥션이 풀에 반환되면서 락이 의도치 않게 해제될 수 있습니다. 따라서 Named Lock 전용 DataSource를 별도로 구성합니다.

**`REQUIRES_NEW`가 필수인 이유:** 비즈니스 트랜잭션이 독립되지 않으면 실제 커밋이 `RELEASE_LOCK` 이후로 지연될 수 있습니다. 이 틈에 다른 스레드가 진입하면 아직 커밋되지 않은 데이터를 보지 못해 중복이 발생합니다.

```
❌ GET_LOCK → 겹침 체크 → INSERT → RELEASE_LOCK → COMMIT (이 틈에 다른 스레드 진입!)
✅ GET_LOCK → 겹침 체크 → INSERT → COMMIT → RELEASE_LOCK (커밋 후 락 해제)
```

**한계:**
- MySQL 전용 기능 (PostgreSQL은 `pg_advisory_lock`으로 별도 구현 필요)
- 하나의 예약에 커넥션 2개 필요 (락용 + 비즈니스용)

---

### 5. 분산 락 (Redis + Redisson)

Redisson의 `RLock`을 사용합니다. DB에 의존하지 않으므로 **다중 서버 환경에서도 동작**합니다.

```
Redis:  ROOM_LOCK:101 = locked (Thread A)
Thread A: tryLock → 성공 → @Transactional { INSERT → COMMIT } → unlock
Thread B: tryLock → 대기 ──────────────────────────────────→ 성공 → 겹침 체크 → 실패
```

**tryLock 파라미터:**
- `waitTime (5초)` — 락 획득 실패 시 최대 대기 시간. 무한 대기를 방지합니다.
- `leaseTime (3초)` — 락 자동 해제 시간. 프로세스 크래시 시 데드락을 방지합니다.

**`isHeldByCurrentThread` 체크가 필요한 이유:** leaseTime이 만료되면 Redisson이 자동으로 락을 해제합니다. 이 상태에서 `unlock()`을 호출하면 `IllegalMonitorStateException`이 발생하므로, 현재 스레드가 락을 보유하고 있는지 확인 후 해제합니다.

Named Lock과 동일하게 `REQUIRES_NEW`로 **커밋 → 락 해제** 순서를 보장해야 합니다.

**한계:**
- Redis 인프라 추가 필요 (모니터링, 장애 대응)
- Redis 단일 인스턴스 장애 시 예약 기능 중단
- leaseTime 설정이 까다로움 (너무 짧으면 동시성 깨짐, 너무 길면 장애 시 대기 시간 증가)

## 전략별 비교

| 전략 | 락 대상 | 다중 DB | 추가 인프라 | 커넥션 사용 | 적합한 상황 |
|---|---|---|---|---|---|
| Pessimistic Lock | DB row | X | 없음 | 1개 | 단일 DB, 간단한 구현 |
| Unique Constraint | DB 제약조건 | O | 없음 | 1개 | 날짜별 가격/재고 관리 필요 시 |
| 이중 방어 | DB row + 제약조건 | X | 없음 | 1개 | 데이터 정합성 최우선 |
| Named Lock | MySQL 이름 기반 | X | 없음 | 2개 | MySQL 환경, 유연한 락 필요 시 |
| **분산 락 (Redis)** | **Redis 키** | **O** | **Redis** | **1개 + Redis** | **다중 서버 환경 (실무 권장)** |

## 테스트

각 전략별로 **2개 스레드(기본)** 및 **10개 스레드(고동시성)** 테스트를 포함합니다. 모든 전략에서 동시 요청 시 정확히 **1건의 예약만 생성**됩니다.

| 전략 | 2스레드 테스트 | 10스레드 테스트 |
|---|---|---|
| Pessimistic Lock | O | O |
| Unique Constraint | O | O |
| 이중 방어 | O | O |
| Named Lock | O | O |
| 분산 락 (Redis) | O | O |