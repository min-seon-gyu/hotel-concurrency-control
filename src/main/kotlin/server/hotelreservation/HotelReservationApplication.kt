package server.hotelreservation

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class HotelReservationApplication

fun main(args: Array<String>) {
    runApplication<HotelReservationApplication>(*args)
}
