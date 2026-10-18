package com.oneryl.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.oneryl.music.data.GoogleVideo
import com.oneryl.music.data.StreamResolver
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/**
 * Reads a googlevideo.com file as a continuous stream while fetching it in
 * [GoogleVideo.CHUNK_BYTES] pieces, which is how YouTube's own players load
 * audio and avoids throttled or refused open-ended requests.
 */
@UnstableApi
class GoogleVideoDataSource(private val client: OkHttpClient) : BaseDataSource(true) {

    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var body: InputStream? = null
    private var position = 0L
    private var end = C.LENGTH_UNSET.toLong()
    private var chunkEnd = 0L
    private var requestNumber = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)

        val totalLength = StreamResolver.queryParameter(dataSpec.uri.toString(), "clen")?.toLongOrNull() ?: -1
        position = dataSpec.position
        end = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> position + dataSpec.length
            totalLength > 0 -> totalLength
            else -> C.LENGTH_UNSET.toLong()
        }
        if (end != C.LENGTH_UNSET.toLong() && position > end) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }

        if (position != end) openChunk(dataSpec)
        opened = true
        transferStarted(dataSpec)
        return if (end == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else end - position
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (end != C.LENGTH_UNSET.toLong() && position >= end) return C.RESULT_END_OF_INPUT
        val spec = checkNotNull(dataSpec)

        var read = readChunk(buffer, offset, length)
        if (read == -1) {
            closeChunk()
            val isLastChunk = if (end == C.LENGTH_UNSET.toLong()) position < chunkEnd else position >= end
            if (isLastChunk) return C.RESULT_END_OF_INPUT
            openChunk(spec)
            read = readChunk(buffer, offset, length)
            if (read == -1) return C.RESULT_END_OF_INPUT
        }
        position += read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        response?.headers?.toMultimap().orEmpty()

    override fun close() {
        closeChunk()
        if (opened) {
            opened = false
            transferEnded()
        }
        dataSpec = null
    }

    private fun readChunk(buffer: ByteArray, offset: Int, length: Int): Int {
        val remaining = if (end == C.LENGTH_UNSET.toLong()) length.toLong() else end - position
        return try {
            checkNotNull(body).read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                e,
                checkNotNull(dataSpec),
                HttpDataSource.HttpDataSourceException.TYPE_READ,
            )
        }
    }

    private fun openChunk(spec: DataSpec) {
        val last = position + GoogleVideo.CHUNK_BYTES
        chunkEnd = if (end == C.LENGTH_UNSET.toLong()) last else minOf(end, last)

        var call = execute(spec, chunkRequest(spec, usePost))
        if (usePost && call.code in POST_REJECTED) {
            call.close()
            usePost = false
            call = execute(spec, chunkRequest(spec, usePost = false))
        }
        if (!call.isSuccessful) {
            val headers = call.headers.toMultimap()
            val code = call.code
            val message = call.message
            call.close()
            throw HttpDataSource.InvalidResponseCodeException(code, message, null, headers, spec, ByteArray(0))
        }
        response = call
        body = call.body.byteStream()
    }

    /**
     * NewPipe's player POSTs with the range in the URL; a server that refuses
     * that gets a plain ranged GET instead, for the rest of the session.
     */
    private fun chunkRequest(spec: DataSpec, usePost: Boolean): Request {
        val url = spec.uri.toString()
        val builder = Request.Builder()
            .apply { spec.httpRequestHeaders.forEach { (name, value) -> header(name, value) } }
        return if (usePost) {
            builder.url(GoogleVideo.chunkUrl(url, requestNumber++, position, chunkEnd - 1))
                .post(GoogleVideo.POST_BODY.toRequestBody())
                .build()
        } else {
            builder.url(url)
                .header("Range", "bytes=$position-${chunkEnd - 1}")
                .build()
        }
    }

    private fun execute(spec: DataSpec, request: Request): Response = try {
        client.newCall(request).execute()
    } catch (e: IOException) {
        throw HttpDataSource.HttpDataSourceException.createForIOException(
            e,
            spec,
            HttpDataSource.HttpDataSourceException.TYPE_OPEN,
        )
    }

    private fun closeChunk() {
        response?.close()
        response = null
        body = null
    }

    class Factory(private val client: OkHttpClient) : androidx.media3.datasource.DataSource.Factory {
        override fun createDataSource() = GoogleVideoDataSource(client)
    }

    private companion object {
        val POST_REJECTED = setOf(400, 405, 411)

        @Volatile
        var usePost = true
    }
}
