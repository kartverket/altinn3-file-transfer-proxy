import no.kartverket.altinn3.persistence.Direction
import no.kartverket.altinn3.persistence.TransitStatus
import no.kartverket.altinn3.persistence.configuration.TransitJdbcConfigImpl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.data.jdbc.core.mapping.JdbcValue
import java.sql.JDBCType

class PostgresEnumTest {
    private val readingConverter =
        TransitJdbcConfigImpl.PostgresEnumReadingConverter(TransitStatus::class.java)
    private val genericWritingConverter =
        TransitJdbcConfigImpl.PostgresEnumWritingConverter()

    @Test
    fun `ReadingConverter returns correct enum`() {
        val jdbcVal = JdbcValue.of("NEW", JDBCType.VARCHAR)
        val result = readingConverter.convert(jdbcVal)
        assertEquals(TransitStatus.NEW, result)
    }

    @Test
    fun `ReadingConverter returns null if JdbcValue is null`() {
        val jdbcVal = JdbcValue.of(null, JDBCType.VARCHAR)
        val result = readingConverter.convert(jdbcVal)
        assertNull(result)
    }

    @Test
    fun `ReadingConverter returns null if string does not match enum constant`() {
        val jdbcVal = JdbcValue.of("someUnknownValue", JDBCType.VARCHAR)
        val result = readingConverter.convert(jdbcVal)
        assertNull(result)
    }

    @Test
    fun `WritingConverter returns correct JdbcValue for an enum`() {
        val input = TransitStatus.COMPLETED
        val jdbcVal = genericWritingConverter.convert(input)
        assertNotNull(jdbcVal)
        assertEquals("COMPLETED", jdbcVal?.value)
        assertEquals(JDBCType.OTHER, jdbcVal?.jdbcType)
    }

    @Test
    fun `WritingConverter works for any Enum`() {
        val directionVal = genericWritingConverter.convert(Direction.IN)
        assertEquals("IN", directionVal?.value)
        assertEquals(JDBCType.OTHER, directionVal?.jdbcType)

        val altinnFilVal = genericWritingConverter.convert(Direction.IN)
        assertEquals("IN", altinnFilVal?.value)
        assertEquals(JDBCType.OTHER, altinnFilVal?.jdbcType)
    }
}
