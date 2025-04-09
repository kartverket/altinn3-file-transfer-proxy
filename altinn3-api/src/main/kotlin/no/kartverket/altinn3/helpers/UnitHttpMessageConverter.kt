package no.kartverket.altinn3.helpers

import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpOutputMessage
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.http.converter.HttpMessageNotWritableException
import java.util.*

class UnitHttpMessageConverter : HttpMessageConverter<Unit> {

    override fun canRead(clazz: Class<*>, mediaType: MediaType?): Boolean {
        return Unit::class.java.isAssignableFrom(clazz)
    }

    override fun canWrite(clazz: Class<*>, mediaType: MediaType?): Boolean {
        return false
    }

    override fun getSupportedMediaTypes(): MutableList<MediaType> {
        return mutableListOf(MediaType.APPLICATION_JSON)
    }

    @Throws(HttpMessageNotReadableException::class)
    override fun read(clazz: Class<out Unit>, inputMessage: HttpInputMessage): Unit {

    }

    @Throws(HttpMessageNotReadableException::class, HttpMessageNotWritableException::class)
    override fun write(unit: Unit, contentType: MediaType?, outputMessage: HttpOutputMessage) {
        throw UnsupportedOperationException("Writing Unit is not supported")
    }
}
