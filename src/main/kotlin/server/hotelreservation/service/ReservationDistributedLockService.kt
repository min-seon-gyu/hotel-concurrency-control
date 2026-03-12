package server.hotelreservation.service

import org.redisson.api.RedissonClient
import org.springframework.stereotype.Service
import server.hotelreservation.domain.Reservation
import server.hotelreservation.dto.ReservationRequest
import java.util.concurrent.TimeUnit

@Service
class ReservationDistributedLockService(
    private val redissonClient: RedissonClient,
    private val reservationService: ReservationService,
) {

    companion object {
        private const val LOCK_PREFIX = "ROOM_LOCK:"
        private const val WAIT_TIME_SECONDS = 5L
        private const val LEASE_TIME_SECONDS = 3L
    }

    fun reserveWithDistributedLock(request: ReservationRequest): Reservation {
        val lockKey = "$LOCK_PREFIX${request.roomNumber}"
        val lock = redissonClient.getLock(lockKey)

        val acquired = lock.tryLock(WAIT_TIME_SECONDS, LEASE_TIME_SECONDS, TimeUnit.SECONDS)
        if (!acquired) {
            throw IllegalStateException("분산 락 획득 실패: $lockKey")
        }

        try {
            return reservationService.reserveInNewTransaction(request)
        } finally {
            if (lock.isHeldByCurrentThread) {
                lock.unlock()
            }
        }
    }
}
