package server.hotelreservation.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import server.hotelreservation.domain.Reservation
import java.time.LocalDate

interface ReservationRepository : JpaRepository<Reservation, Long> {

    @Query(
        """
        SELECT CASE WHEN EXISTS (
            SELECT 1 FROM Reservation r
            WHERE r.room.id = :roomId
            AND r.checkInDate < :checkOutDate
            AND r.checkOutDate > :checkInDate
        ) THEN true ELSE false END
        """
    )
    fun existsOverlapping(
        roomId: Long,
        checkInDate: LocalDate,
        checkOutDate: LocalDate,
    ): Boolean
}
