package server.hotelreservation

import com.redis.testcontainers.RedisContainer
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

@TestConfiguration(proxyBeanMethods = false)
class TestContainersConfig {

    @Bean("mysqlContainer")
    fun mysqlContainer(): GenericContainer<*> =
        GenericContainer("mysql:8.0")
            .withExposedPorts(3306)
            .withEnv("MYSQL_ROOT_PASSWORD", "root")
            .withEnv("MYSQL_DATABASE", "hotel")
            .waitingFor(Wait.forListeningPort())

    @Bean
    fun mysqlProperties(
        @Qualifier("mysqlContainer") mysql: GenericContainer<*>,
    ): DynamicPropertyRegistrar =
        DynamicPropertyRegistrar { registry ->
            val host = mysql.host
            val port = mysql.getMappedPort(3306)
            registry.add("spring.datasource.url") {
                "jdbc:mysql://$host:$port/hotel?useSSL=false&allowPublicKeyRetrieval=true"
            }
            registry.add("spring.datasource.username") { "root" }
            registry.add("spring.datasource.password") { "root" }
        }

    @Bean("redisContainer")
    fun redisContainer(): RedisContainer =
        RedisContainer("redis:7")

    @Bean
    fun redisProperties(
        @Qualifier("redisContainer") redis: RedisContainer,
    ): DynamicPropertyRegistrar =
        DynamicPropertyRegistrar { registry ->
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
        }
}
