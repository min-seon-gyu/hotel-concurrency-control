package server.hotelreservation.concurrency

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import server.hotelreservation.TestContainersConfig
import server.hotelreservation.domain.Room
import server.hotelreservation.dto.ReservationRequest
import server.hotelreservation.repository.ReservationRepository
import server.hotelreservation.repository.RoomDateRepository
import server.hotelreservation.repository.RoomRepository
import server.hotelreservation.service.ReservationDistributedLockService
import server.hotelreservation.service.ReservationNamedLockService
import server.hotelreservation.service.ReservationService
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
@Import(TestContainersConfig::class)
class ConcurrentReservationTest {

    private val log = LoggerFactory.getLogger(javaClass)

    @Autowired lateinit var reservationService: ReservationService
    @Autowired lateinit var namedLockService: ReservationNamedLockService
    @Autowired lateinit var distributedLockService: ReservationDistributedLockService
    @Autowired lateinit var reservationRepository: ReservationRepository
    @Autowired lateinit var roomDateRepository: RoomDateRepository
    @Autowired lateinit var roomRepository: RoomRepository

    private val checkIn: LocalDate = LocalDate.of(2026, 5, 1)
    private val checkOut: LocalDate = LocalDate.of(2026, 5, 3)

    @BeforeEach
    fun setUp() {
        roomDateRepository.deleteAll()
        reservationRepository.deleteAll()
        roomRepository.deleteAll()
        roomRepository.save(Room(roomNumber = 101))
    }

    private fun executeConcurrently(
        threadCount: Int = 2,
        action: (Int) -> Unit,
    ): Pair<Int, Int> {
        val readyLatch = CountDownLatch(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

        for (i in 1..threadCount) {
            executor.submit {
                try {
                    readyLatch.countDown()
                    startLatch.await()
                    action(i)
                    successCount.incrementAndGet()
                } catch (e: Exception) {
                    failCount.incrementAndGet()
                    log.info("[Thread-{}] 실패: {}", i, e.message)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        readyLatch.await()
        startLatch.countDown()
        doneLatch.await()
        executor.shutdown()
        return successCount.get() to failCount.get()
    }

    private fun createRequest(): ReservationRequest =
        ReservationRequest(
            roomNumber = 101,
            checkInDate = checkIn,
            checkOutDate = checkOut,
        )

    // =============================================
    // Pessimistic Lock
    // =============================================

    @Test
    @DisplayName("Pessimistic Lock - 동시에 2명이 같은 방을 예약하면 1명만 성공한다")
    fun pessimisticLock_preventsDoubleBooking() {
        val (success, fail) = executeConcurrently { i ->
            reservationService.reserveWithPessimisticLock(createRequest())
        }

        assertResult(success = success, fail = fail, expectedSuccess = 1)
    }

    @Test
    @DisplayName("Pessimistic Lock - 10명이 동시에 예약해도 1명만 성공한다")
    fun pessimisticLock_highConcurrency() {
        val (success, _) = executeConcurrently(threadCount = 10) { i ->
            reservationService.reserveWithPessimisticLock(createRequest())
        }

        assertResult(success = success, expectedSuccess = 1)
    }

    // =============================================
    // Unique Constraint
    // =============================================

    @Test
    @DisplayName("Unique Constraint - 동시에 2명이 같은 방을 예약하면 1명만 성공한다")
    fun uniqueConstraint_preventsDoubleBooking() {
        val (success, fail) = executeConcurrently { i ->
            reservationService.reserveWithUniqueConstraint(createRequest())
        }

        assertResult(success = success, fail = fail, expectedSuccess = 1)
    }

    @Test
    @DisplayName("Unique Constraint - 10명이 동시에 예약해도 1명만 성공한다")
    fun uniqueConstraint_highConcurrency() {
        val (success, _) = executeConcurrently(threadCount = 10) { i ->
            reservationService.reserveWithUniqueConstraint(createRequest())
        }

        assertResult(success = success, expectedSuccess = 1)
    }

    // =============================================
    // 이중 방어 (Pessimistic Lock + Unique Constraint)
    // =============================================

    @Test
    @DisplayName("이중 방어 - 동시에 2명이 같은 방을 예약하면 1명만 성공한다")
    fun doubleGuard_preventsDoubleBooking() {
        val (success, fail) = executeConcurrently { i ->
            reservationService.reserveWithDoubleGuard(createRequest())
        }

        assertResult(success = success, fail = fail, expectedSuccess = 1)
    }

    @Test
    @DisplayName("이중 방어 - 10명이 동시에 예약해도 1명만 성공한다")
    fun doubleGuard_highConcurrency() {
        val (success, _) = executeConcurrently(threadCount = 10) { i ->
            reservationService.reserveWithDoubleGuard(createRequest())
        }

        assertResult(success = success, expectedSuccess = 1)
    }

    // =============================================
    // Named Lock (MySQL GET_LOCK)
    // =============================================

    @Test
    @DisplayName("Named Lock - 동시에 2명이 같은 방을 예약하면 1명만 성공한다")
    fun namedLock_preventsDoubleBooking() {
        val (success, fail) = executeConcurrently { i ->
            namedLockService.reserveWithNamedLock(createRequest())
        }

        assertResult(success = success, fail = fail, expectedSuccess = 1)
    }

    @Test
    @DisplayName("Named Lock - 10명이 동시에 예약해도 1명만 성공한다")
    fun namedLock_highConcurrency() {
        val (success, _) = executeConcurrently(threadCount = 10) { i ->
            namedLockService.reserveWithNamedLock(createRequest())
        }

        assertResult(success = success, expectedSuccess = 1)
    }

    // =============================================
    // 분산 락 (Redis + Redisson)
    // =============================================

    @Test
    @DisplayName("분산 락 - 동시에 2명이 같은 방을 예약하면 1명만 성공한다")
    fun distributedLock_preventsDoubleBooking() {
        val (success, fail) = executeConcurrently { i ->
            distributedLockService.reserveWithDistributedLock(createRequest())
        }

        assertResult(success = success, fail = fail, expectedSuccess = 1)
    }

    @Test
    @DisplayName("분산 락 - 10명이 동시에 예약해도 1명만 성공한다")
    fun distributedLock_highConcurrency() {
        val (success, _) = executeConcurrently(threadCount = 10) { i ->
            distributedLockService.reserveWithDistributedLock(createRequest())
        }

        assertResult(success = success, expectedSuccess = 1)
    }

    // =============================================
    // 전략 비교
    // =============================================

    @Test
    @DisplayName("전략 비교 - 모든 전략의 동시성 처리 결과를 비교한다")
    fun compareSummary() {
        data class Result(val strategy: String, val success: Int, val fail: Int, val dbCount: Long)

        val strategies = listOf(
            "Pessimistic Lock" to { i: Int -> reservationService.reserveWithPessimisticLock(createRequest()); Unit },
            "Unique Constraint" to { i: Int -> reservationService.reserveWithUniqueConstraint(createRequest()); Unit },
            "이중 방어" to { i: Int -> reservationService.reserveWithDoubleGuard(createRequest()); Unit },
            "Named Lock" to { i: Int -> namedLockService.reserveWithNamedLock(createRequest()); Unit },
            "분산 락 (Redis)" to { i: Int -> distributedLockService.reserveWithDistributedLock(createRequest()); Unit },
        )

        val results = strategies.map { (name, action) ->
            roomDateRepository.deleteAll()
            reservationRepository.deleteAll()

            val (success, fail) = executeConcurrently(action = action)
            Result(name, success, fail, reservationRepository.count())
        }

        log.info("\n====================================================")
        log.info("          동시성 처리 전략 비교 결과 (실무)")
        log.info("====================================================")
        log.info("{}", String.format("%-25s | 성공 | 실패 | DB수 | 안전?", "전략"))
        log.info("----------------------------------------------------")
        for (r in results) {
            val safe = if (r.dbCount <= 1L) "O" else "X"
            log.info("{}", String.format("%-23s | %4d | %4d | %4d | %s", r.strategy, r.success, r.fail, r.dbCount, safe))
        }
        log.info("====================================================")

        results.forEach { assertThat(it.dbCount).isEqualTo(1L) }
    }

    private fun assertResult(success: Int, fail: Int? = null, expectedSuccess: Int) {
        val totalReservations = reservationRepository.count()
        log.info("성공: {}, 실패: {}, DB 예약 수: {}", success, fail ?: (totalReservations - success), totalReservations)

        assertThat(success).isEqualTo(expectedSuccess)
        assertThat(totalReservations).isEqualTo(expectedSuccess.toLong())
    }
}
