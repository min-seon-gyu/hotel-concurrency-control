package server.hotelreservation.repository

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import server.hotelreservation.domain.Room

interface RoomRepository : JpaRepository<Room, Long> {

    fun findByRoomNumber(roomNumber: Int): Room?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Room r WHERE r.roomNumber = :roomNumber")
    fun findByRoomNumberWithLock(roomNumber: Int): Room?
}
