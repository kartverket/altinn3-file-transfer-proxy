package no.kartverket.altinn3.persistence.configuration

import com.zaxxer.hikari.HikariDataSource
import no.kartverket.altinn3.persistence.*
import org.postgresql.util.PGobject
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.*
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.data.jdbc.core.convert.DataAccessStrategy
import org.springframework.data.jdbc.core.convert.JdbcConverter
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions
import org.springframework.data.jdbc.core.convert.RelationResolver
import org.springframework.data.jdbc.core.mapping.JdbcMappingContext
import org.springframework.data.jdbc.core.mapping.JdbcValue
import org.springframework.data.jdbc.repository.support.JdbcRepositoryFactory
import org.springframework.data.mapping.callback.EntityCallbacks
import org.springframework.data.relational.RelationalManagedTypes
import org.springframework.data.relational.core.dialect.Dialect
import org.springframework.data.relational.core.mapping.NamingStrategy
import org.springframework.data.relational.core.mapping.RelationalMappingContext
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.JDBCType
import java.util.*
import javax.sql.DataSource

@Configuration
@EnableConfigurationProperties(DataSourceProperties::class)
class TransitDataSourceConfig {
    @Bean("transitDataSourceProperties")
    @ConfigurationProperties("spring.datasource.transit")
    fun transitDataSourceProperties() = DataSourceProperties()

    @Primary
    @Bean("transitDataSource")
    @FlywayDataSource
    @ConfigurationProperties("spring.datasource.transit.hikari")
    fun transitDataSource(
        @Qualifier("transitDataSourceProperties") transitDataSourceProperties: DataSourceProperties
    ): HikariDataSource =
        transitDataSourceProperties
            .initializeDataSourceBuilder()
            .type(HikariDataSource::class.java)
            .build()

    @Primary
    @Bean("transitJdbcOperations")
    fun transitJdbcOperations(
        @Qualifier("transitDataSource") ds: DataSource
    ): NamedParameterJdbcOperations =
        NamedParameterJdbcTemplate(ds)

    @Primary
    @Bean("transitTxManager")
    fun transitTxManager(
        @Qualifier("transitDataSource") ds: DataSource
    ): PlatformTransactionManager =
        DataSourceTransactionManager(ds)

    @Bean("transitTransactionTemplate")
    fun transactionTemplate(@Qualifier("transitTxManager") transactionManager: PlatformTransactionManager) =
        TransactionTemplate(transactionManager)
}

@Component
class TransitJdbcConfigImpl(
    @Qualifier("transitJdbcOperations") operations: NamedParameterJdbcOperations
) :
    JdbcConfig(operations) {

    @ReadingConverter
    class PostgresEnumReadingConverter<T : Enum<*>>(
        private val enumClass: Class<T>,
    ) : Converter<JdbcValue, T> {

        override fun convert(source: JdbcValue): T? {
            val value = source.value ?: return null
            val stringVal = value.toString().uppercase()
            return enumClass.enumConstants.firstOrNull { it.name.uppercase() == stringVal }
        }
    }

    @WritingConverter
    class PostgresEnumWritingConverter : Converter<Enum<*>, JdbcValue?> {
        override fun convert(source: Enum<*>): JdbcValue? {
            return kotlin.runCatching {
                JdbcValue.of(source.name, JDBCType.OTHER)
            }.getOrNull()
        }
    }

    @ReadingConverter
    class JsonReadingConverter : Converter<PGobject, String> {
        override fun convert(source: PGobject): String {
            return source.value.toString()
        }
    }

    @WritingConverter
    class JsonWritingConverter : Converter<String, JdbcValue> {
        override fun convert(source: String): JdbcValue {
            return JdbcValue.of(source, JDBCType.OTHER)
        }
    }

    override fun userConverters(): List<*> {
        return listOf(
            PostgresEnumReadingConverter(Direction::class.java),
            PostgresEnumReadingConverter(TransitStatus::class.java),
            JsonReadingConverter(),
            JsonWritingConverter(),
            PostgresEnumWritingConverter()
        )
    }
}

@Import(TransitJdbcConfigImpl::class, TransitDataSourceConfig::class)
@EnableAutoConfiguration(
    exclude = [DataSourceAutoConfiguration::class, JdbcRepositoriesAutoConfiguration::class]
)
@Configuration(proxyBeanMethods = false)
@Suppress("TooManyFunctions")
class TransitRepositoryConfig {
    private val logger = LoggerFactory.getLogger(this::class.java)

    @Primary
    @Bean("transitJdbcManagedTypes")
    @Throws(
        ClassNotFoundException::class
    )
    fun transitJdbcManagedTypes(transitJdbcConfigImpl: TransitJdbcConfigImpl): RelationalManagedTypes {
        return transitJdbcConfigImpl.jdbcManagedTypes()
    }

    @Primary
    @Bean("transitJdbcMappingContext")
    fun transitJdbcMappingContext(
        namingStrategy: Optional<NamingStrategy?>,
        @Qualifier("transitJdbcCustomConversions") customConversions: JdbcCustomConversions,
        @Qualifier("transitJdbcManagedTypes") jdbcManagedTypes: RelationalManagedTypes,
        transitJdbcConfigImpl: TransitJdbcConfigImpl
    ): JdbcMappingContext {
        return transitJdbcConfigImpl.jdbcMappingContext(namingStrategy, customConversions, jdbcManagedTypes)
    }

    @Primary
    @Bean("transitJdbcConverter")
    @Suppress("LongParameterList")
    fun transitJdbcConverter(
        @Qualifier("transitJdbcMappingContext") mappingContext: JdbcMappingContext,
        @Qualifier("transitJdbcOperations") operations: NamedParameterJdbcOperations,
        @Qualifier("transitDataAccessStrategy") @Lazy relationResolver: RelationResolver,
        @Qualifier("transitJdbcCustomConversions") conversions: JdbcCustomConversions,
        @Qualifier("transitJdbcDialect") dialect: Dialect,
        transitJdbcConfigImpl: TransitJdbcConfigImpl
    ): JdbcConverter {
        return transitJdbcConfigImpl.jdbcConverter(mappingContext, operations, relationResolver, conversions, dialect)
    }

    @Primary
    @Bean("transitJdbcCustomConversions")
    fun transitJdbcCustomConversions(transitJdbcConfigImpl: TransitJdbcConfigImpl): JdbcCustomConversions =
        transitJdbcConfigImpl.jdbcCustomConversions()

    @Bean("transitEntityCallbacks")
    fun transitEntityCallbacks(
        applicationContext: ApplicationContext,
    ): EntityCallbacks =
        EntityCallbacks.create(applicationContext)

    @Primary
    @Bean("transitJdbcAggregateTemplate")
    fun transitJdbcAggregateTemplate(
        applicationContext: ApplicationContext,
        @Qualifier("transitJdbcMappingContext") mappingContext: JdbcMappingContext,
        @Qualifier("transitJdbcConverter") converter: JdbcConverter,
        @Qualifier("transitDataAccessStrategy") dataAccessStrategy: DataAccessStrategy,
        transitJdbcConfigImpl: TransitJdbcConfigImpl
    ): JdbcAggregateTemplate {
        return transitJdbcConfigImpl.jdbcAggregateTemplate(
            applicationContext,
            mappingContext,
            converter,
            dataAccessStrategy
        ).apply {
            this.setEntityLifecycleEventsEnabled(true)
        }
    }

    @Primary
    @Bean("transitDataAccessStrategy")
    fun transitDataAccessStrategy(
        @Qualifier("transitJdbcOperations") operations: NamedParameterJdbcOperations,
        @Qualifier("transitJdbcConverter") jdbcConverter: JdbcConverter,
        @Qualifier("transitJdbcMappingContext") context: JdbcMappingContext,
        @Qualifier("transitJdbcDialect") dialect: Dialect,
        transitJdbcConfigImpl: TransitJdbcConfigImpl
    ): DataAccessStrategy {
        return transitJdbcConfigImpl.dataAccessStrategyBean(operations, jdbcConverter, context, dialect)
    }

    @Primary
    @Bean("transitJdbcDialect")
    fun transitJdbcDialect(
        @Qualifier("transitJdbcOperations") operations: NamedParameterJdbcOperations,
        transitJdbcConfigImpl: TransitJdbcConfigImpl
    ): Dialect {
        return transitJdbcConfigImpl.jdbcDialect(operations).also {
            logger.info("Using dialect ${it.javaClass}")
        }
    }

    @Bean
    @Qualifier("transit")
    @Suppress("LongParameterList")
    fun altinnFilOverviewRepository(
        @Qualifier("transitDataAccessStrategy") dataAccessStrategy: DataAccessStrategy,
        @Qualifier("transitJdbcMappingContext") relationalMappingContext: RelationalMappingContext,
        @Qualifier("transitJdbcDialect") dialect: Dialect,
        @Qualifier("transitJdbcConverter") converter: JdbcConverter,
        @Qualifier("transitJdbcOperations") operations: NamedParameterJdbcOperations,
        @Qualifier("transitEntityCallbacks") entityCallbacks: EntityCallbacks,
        applicationEventPublisher: ApplicationEventPublisher,
    ): AltinnFilOverviewRepository =
        JdbcRepositoryFactory(
            dataAccessStrategy,
            relationalMappingContext,
            converter,
            dialect,
            applicationEventPublisher,
            operations
        ).apply { this.setEntityCallbacks(entityCallbacks) }
            .getRepository(AltinnFilOverviewRepository::class.java)

    @Bean
    @Qualifier("transit")
    @Suppress("LongParameterList")
    fun altinnEventRepository(
        @Qualifier("transitDataAccessStrategy") dataAccessStrategy: DataAccessStrategy,
        @Qualifier("transitJdbcMappingContext") relationalMappingContext: RelationalMappingContext,
        @Qualifier("transitJdbcDialect") dialect: Dialect,
        @Qualifier("transitJdbcConverter") converter: JdbcConverter,
        @Qualifier("transitJdbcOperations") operations: NamedParameterJdbcOperations,
        @Qualifier("transitEntityCallbacks") entityCallbacks: EntityCallbacks,
        applicationEventPublisher: ApplicationEventPublisher,
    ): AltinnEventRepository =
        JdbcRepositoryFactory(
            dataAccessStrategy,
            relationalMappingContext,
            converter,
            dialect,
            applicationEventPublisher,
            operations
        ).apply { this.setEntityCallbacks(entityCallbacks) }
            .getRepository(AltinnEventRepository::class.java)

    @Bean
    @Qualifier("transit")
    @Suppress("LongParameterList")
    fun altinnFailedEventRepository(
        @Qualifier("transitDataAccessStrategy") dataAccessStrategy: DataAccessStrategy,
        @Qualifier("transitJdbcMappingContext") relationalMappingContext: RelationalMappingContext,
        @Qualifier("transitJdbcDialect") dialect: Dialect,
        @Qualifier("transitJdbcConverter") converter: JdbcConverter,
        @Qualifier("transitJdbcOperations") operations: NamedParameterJdbcOperations,
        @Qualifier("transitEntityCallbacks") entityCallbacks: EntityCallbacks,
        applicationEventPublisher: ApplicationEventPublisher,
    ): AltinnFailedEventRepository {
        return JdbcRepositoryFactory(
            dataAccessStrategy,
            relationalMappingContext,
            converter,
            dialect,
            applicationEventPublisher,
            operations
        ).apply { this.setEntityCallbacks(entityCallbacks) }
            .getRepository(AltinnFailedEventRepository::class.java)
    }

    @Bean
    @Qualifier("transit")
    @Suppress("LongParameterList")
    fun altinnFilRepository(
        @Qualifier("transitDataAccessStrategy") dataAccessStrategy: DataAccessStrategy,
        @Qualifier("transitJdbcMappingContext") relationalMappingContext: RelationalMappingContext,
        @Qualifier("transitJdbcDialect") dialect: Dialect,
        @Qualifier("transitJdbcConverter") converter: JdbcConverter,
        @Qualifier("transitJdbcOperations") operations: NamedParameterJdbcOperations,
        @Qualifier("transitEntityCallbacks") entityCallbacks: EntityCallbacks,
        applicationEventPublisher: ApplicationEventPublisher,
    ): AltinnFilRepository {
        return JdbcRepositoryFactory(
            dataAccessStrategy,
            relationalMappingContext,
            converter,
            dialect,
            applicationEventPublisher,
            operations
        ).apply { this.setEntityCallbacks(entityCallbacks) }
            .getRepository(AltinnFilRepository::class.java)
    }
}
