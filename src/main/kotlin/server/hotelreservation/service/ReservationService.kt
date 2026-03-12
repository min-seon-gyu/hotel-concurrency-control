package server.hotelreservation.service

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import server.hotelreservation.domain.Reservation
import server.hotelreservation.domain.Room
import server.hotelreservation.domain.RoomDate
import server.hotelreservation.dto.ReservationRequest
import server.hotelreservation.repository.ReservationRepository
import server.hotelreservation.repository.RoomDateRepository
import server.hotelreservation.repository.RoomRepository

@Service
class ReservationService(
    private val roomRepository: RoomRepository,
    private val reservationRepository: ReservationRepository,
    private val roomDateRepository: RoomDateRepository,
) {

    @Transactional
    fun reserveWithPessimisticLock(request: ReservationRequest): Reservation {
        request.validate()

        val room = roomRepository.findByRoomNumberWithLock(request.roomNumber)
            ?: throw NoSuchElementException("존재하지 않는 객실입니다. roomNumber=${request.roomNumber}")

        validateNotOverlapping(room, request)
        return createReservation(room, request)
    }

    @Transactional
    fun reserveWithUniqueConstraint(request: ReservationRequest): Reservation {
        request.validate()

        val room = roomRepository.findByRoomNumber(request.roomNumber)
            ?: throw NoSuchElementException("존재하지 않는 객실입니다. roomNumber=${request.roomNumber}")

        val reservation = createReservation(room, request)
        saveRoomDates(room, reservation)
        return reservation
    }

    @Transactional
    fun reserveWithDoubleGuard(request: ReservationRequest): Reservation {
        request.validate()

        val room = roomRepository.findByRoomNumberWithLock(request.roomNumber)
            ?: throw NoSuchElementException("존재하지 않는 객실입니다. roomNumber=${request.roomNumber}")

        validateNotOverlapping(room, request)
        val reservation = createReservation(room, request)
        saveRoomDates(room, reservation)
        return reservation
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun reserveInNewTransaction(request: ReservationRequest): Reservation {
        request.validate()

        val room = roomRepository.findByRoomNumber(request.roomNumber)
            ?: throw NoSuchElementException("존재하지 않는 객실입니다. roomNumber=${request.roomNumber}")

        validateNotOverlapping(room, request)
        return createReservation(room, request)
    }

    private fun validateNotOverlapping(room: Room, request: ReservationRequest) {
        if (reservationRepository.existsOverlapping(room.id, request.checkInDate, request.checkOutDate)) {
            throw IllegalStateException("해당 날짜에 이미 예약된 객실입니다. roomNumber=${request.roomNumber}")
        }
    }

    private fun createReservation(room: Room, request: ReservationRequest): Reservation {
        return reservationRepository.save(
            Reservation(
                room = room,
                checkInDate = request.checkInDate,
                checkOutDate = request.checkOutDate,
            )
        )
    }

    private fun saveRoomDates(room: Room, reservation: Reservation) {
        try {
            val roomDates = reservation.dateRange().map { date ->
                RoomDate(room = room, reservedDate = date, reservation = reservation)
            }
            roomDateRepository.saveAll(roomDates)
            roomDateRepository.flush()
        } catch (e: DataIntegrityViolationException) {
            throw IllegalStateException("해당 날짜에 이미 예약된 객실입니다. roomNumber=${reservation.room.roomNumber}")
        }
    }
}
