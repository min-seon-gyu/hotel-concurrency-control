package server.hotelreservation.repository

import org.springframework.data.jpa.repository.JpaRepository
import server.hotelreservation.domain.RoomDate

interface RoomDateRepository : JpaRepository<RoomDate, Long>
