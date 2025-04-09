package no.kartverket.altinn3.helpers

import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpOutputMessage
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.http.converter.HttpMessageNotWritableException
import org.springframework.util.StreamUtils
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.*

class UUIDHttpMessageConverter : HttpMessageConverter<UUID> {

    override fun canRead(clazz: Class<*>, mediaType: MediaType?): Boolean {
        return clazz == UUID::class.java && mediaType != null && mediaType.isCompatibleWith(MediaType.TEXT_PLAIN)
    }

    override fun canWrite(clazz: Class<*>, mediaType: MediaType?): Boolean {
        return false
    }

    override fun getSupportedMediaTypes(): List<MediaType> {
        return listOf(MediaType.TEXT_PLAIN)
    }

    @Throws(IOException::class, HttpMessageNotReadableException::class)
    override fun read(clazz: Class<out UUID>, inputMessage: HttpInputMessage): UUID {
        val bytes = StreamUtils.copyToByteArray(inputMessage.body)
        val uuidString = String(bytes, StandardCharsets.UTF_8)
        return UUID.fromString(uuidString)
    }

    @Throws(IOException::class, HttpMessageNotWritableException::class)
    override fun write(t: UUID, contentType: MediaType?, outputMessage: HttpOutputMessage) {
        throw HttpMessageNotWritableException("no.kartverket.altinn3.helpers.UUIDHttpMessageConverter only supports reading UUIDs.")
    }
}
