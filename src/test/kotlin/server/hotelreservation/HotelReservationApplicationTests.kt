package server.hotelreservation

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(TestContainersConfig::class)
class HotelReservationApplicationTests {

    @Test
    fun contextLoads() {
    }
}
