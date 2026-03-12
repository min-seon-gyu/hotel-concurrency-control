package server.hotelreservation.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

@Configuration
class DataSourceConfig(
    @Value("\${spring.datasource.url}") private val url: String,
    @Value("\${spring.datasource.username}") private val username: String,
    @Value("\${spring.datasource.password}") private val password: String,
    @Value("\${spring.datasource.driver-class-name}") private val driverClassName: String,
) {

    /**
     * Named Lock 전용 DataSource.
     *
     * MySQL GET_LOCK은 커넥션 단위로 동작한다.
     * 비즈니스 로직과 같은 커넥션을 쓰면 트랜잭션 커밋/롤백 시 락이 해제될 수 있다.
     * 따라서 락 획득/해제 전용 커넥션 풀을 별도로 구성한다.
     *
     * 풀 사이즈를 작게 잡아 커넥션 고갈을 방지한다.
     */
    @Bean
    fun namedLockDataSource(): DataSource {
        val config = HikariConfig()
        config.jdbcUrl = url
        config.username = username
        config.password = password
        config.driverClassName = driverClassName
        config.poolName = "NamedLockPool"
        config.maximumPoolSize = 5
        return HikariDataSource(config)
    }
}
