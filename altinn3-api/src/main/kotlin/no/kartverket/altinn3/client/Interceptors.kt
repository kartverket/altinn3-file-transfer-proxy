package no.kartverket.altinn3.client

import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.module.kotlin.jsonMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import java.io.InputStream
import java.nio.charset.Charset
import kotlin.math.min


inline fun <reified T> debugRequestInterceptor() = debugRequestInterceptor(T::class.java)

fun <T> debugRequestInterceptor(logger: Class<T>) = object : ClientHttpRequestInterceptor {

    val logger = LoggerFactory.getLogger(logger)

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution
    ): ClientHttpResponse {
        if (this.logger.isDebugEnabled) {
            this.logger.debug(
                "Client request: {}, \nheaders:\n{}, \nbody:\n{}\nEnd Client request",
                request.uri,
                request.headers,
                body.toString(Charset.defaultCharset())
            )
        }
        val response = execution.execute(request, body)
        return response
    }

}

inline fun <reified T> debugResponseInterceptor(maxBodyLength: Int = 1024) =
    debugResponseInterceptor(T::class.java, maxBodyLength)

fun <T> debugResponseInterceptor(loggerClass: Class<T>, maxBodyLength: Int = 1024) =
    object : ClientHttpRequestInterceptor {

        val logger = LoggerFactory.getLogger(loggerClass)

        override fun intercept(
            request: HttpRequest,
            body: ByteArray,
            execution: ClientHttpRequestExecution
        ): ClientHttpResponse {
            val response = execution.execute(request, body)
            if (this.logger.isDebugEnabled) {
                val bis = response.body.buffered(0x10000)
                bis.mark(Int.MAX_VALUE)
                val contentType = response.headers.contentType
                val respBody = when {
                    //json pretty print it
                    contentType?.subtype?.contains("json") == true -> jsonMapper {
                        this.configure(StreamReadFeature.AUTO_CLOSE_SOURCE, false)
                    }.let {
                        val json = it.reader().readTree(bis)
                        val jsonString = it.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(json)
                        jsonString.substring(0, min(jsonString.length, maxBodyLength))
                    }
                    //else print out part of response
                    else -> bis.readNBytes(maxBodyLength).toString(Charset.defaultCharset())
                }
                bis.reset()
                this.logger.debug(
                    "Client response: {}, \nheaders:\n{}, \nbody:\n{}\nEnd Client response",
                    request.uri,
                    request.headers,
                    respBody
                )
                return object : ClientHttpResponse by response {
                    override fun getBody(): InputStream {
                        return bis
                    }
                }

            }
            return response
        }

    }