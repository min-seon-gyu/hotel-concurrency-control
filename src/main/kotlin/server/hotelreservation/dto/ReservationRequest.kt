package server.hotelreservation.dto

import java.time.LocalDate

data class ReservationRequest(
    val roomNumber: Int,
    val checkInDate: LocalDate,
    val checkOutDate: LocalDate,
) {
    fun validate() {
        require(checkInDate.isBefore(checkOutDate)) {
            "체크인 날짜는 체크아웃 날짜보다 이전이어야 합니다."
        }
    }
}
