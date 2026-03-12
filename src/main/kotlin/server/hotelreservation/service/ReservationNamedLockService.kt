package server.hotelreservation.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import server.hotelreservation.domain.Reservation
import server.hotelreservation.dto.ReservationRequest
import java.sql.Connection
import javax.sql.DataSource

@Service
class ReservationNamedLockService(
    @Qualifier("namedLockDataSource")
    private val namedLockDataSource: DataSource,
    private val reservationService: ReservationService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun reserveWithNamedLock(request: ReservationRequest): Reservation {
        val lockName = "ROOM_LOCK_${request.roomNumber}"
        val connection = namedLockDataSource.connection

        try {
            getLock(connection, lockName, 5)
            try {
                return reservationService.reserveInNewTransaction(request)
            } finally {
                releaseLock(connection, lockName)
            }
        } finally {
            connection.close()
        }
    }

    private fun getLock(connection: Connection, lockName: String, timeout: Int) {
        connection.prepareStatement("SELECT GET_LOCK(?, ?)").use { stmt ->
            stmt.setString(1, lockName)
            stmt.setInt(2, timeout)
            stmt.executeQuery().use { rs ->
                if (!rs.next() || rs.getInt(1) != 1) {
                    throw IllegalStateException("Named Lock 획득 실패: $lockName")
                }
            }
        }
    }

    private fun releaseLock(connection: Connection, lockName: String) {
        try {
            connection.prepareStatement("SELECT RELEASE_LOCK(?)").use { stmt ->
                stmt.setString(1, lockName)
                stmt.executeQuery()
            }
        } catch (e: Exception) {
            log.warn("Named Lock 해제 실패: {} - {}", lockName, e.message)
        }
    }
}
