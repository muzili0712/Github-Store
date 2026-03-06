package zed.rainxch.core.data.services

import co.touchlab.kermit.Logger
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.isSuccess
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zed.rainxch.core.domain.model.DownloadProgress
import zed.rainxch.core.domain.network.Downloader
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

class DesktopDownloader(
    private val http: HttpClient,
    private val files: FileLocationsProvider,
) : Downloader {

    override fun download(url: String, suggestedFileName: String?): Flow<DownloadProgress> =
        callbackFlow {
            coroutineScope {
                val dir = File(files.userDownloadsDir())
                if (!dir.exists()) dir.mkdirs()

                val rawName = suggestedFileName?.takeIf { it.isNotBlank() }
                    ?: url.substringAfterLast('/').substringBefore('?').substringBefore('#')
                        .ifBlank { "asset-${UUID.randomUUID()}" }
                val safeName = rawName.substringAfterLast('/').substringAfterLast('\\')
                require(safeName.isNotBlank() && safeName != "." && safeName != "..") {
                    "Invalid file name: $rawName"
                }
                val outFile = File(dir, safeName)

                if (outFile.exists()) {
                    Logger.d { "Deleting existing file before download: ${outFile.absolutePath}" }
                    outFile.delete()
                }

                Logger.d { "Downloading: $url to ${outFile.absolutePath}" }

                val response: HttpResponse = http.get(url)
                if (!response.status.isSuccess()) {
                    close(IllegalStateException("Download failed: HTTP ${response.status.value}"))
                    return@coroutineScope
                }

                val total = response.headers["Content-Length"]?.toLongOrNull()
                val channel = response.bodyAsChannel()

                val downloaded = AtomicLong(0L)

                trySend(DownloadProgress(0L, total, if (total != null && total > 0) 0 else null))

                val downloadJob = launch(Dispatchers.IO) {
                    try {
                        FileOutputStream(outFile).use { fos ->
                            val fc = fos.channel

                            while (isActive) {
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                val bytesRead = channel.readAvailable(buffer, 0, buffer.size)
                                if (bytesRead == -1) break

                                if (bytesRead > 0) {
                                    val byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead)
                                    while (byteBuffer.hasRemaining()) {
                                        fc.write(byteBuffer)
                                    }
                                    downloaded.addAndGet(bytesRead.toLong())
                                }
                            }
                        }
                        Logger.d { "File write complete: ${outFile.absolutePath}" }
                    } catch (e: CancellationException) {
                        if (outFile.exists()) {
                            outFile.delete()
                            Logger.d { "Deleted partial file after cancellation: ${outFile.absolutePath}" }
                        }
                        throw e
                    } catch (e: Exception) {
                        if (outFile.exists()) {
                            outFile.delete()
                        }
                        throw e
                    }
                }

                val progressJob = launch {
                    while (isActive && downloadJob.isActive) {
                        val current = downloaded.get()
                        val percent = if (total != null && total > 0) {
                            ((current * 100L) / total).toInt()
                        } else null
                        trySend(DownloadProgress(current, total, percent))
                        delay(50L)
                    }
                }

                try {
                    downloadJob.join()
                    progressJob.cancel()

                    val finalDownloaded = total ?: outFile.length()
                    trySend(DownloadProgress(finalDownloaded, total, 100))
                    Logger.d { "Download complete: ${outFile.absolutePath}" }

                    close()
                } catch (e: CancellationException) {
                    downloadJob.cancel()
                    progressJob.cancel()
                    close(e)
                } catch (e: Exception) {
                    downloadJob.cancel()
                    progressJob.cancel()
                    close(e)
                }
            }

            awaitClose { }
        }.flowOn(Dispatchers.Default).buffer(Channel.CONFLATED)

    override suspend fun saveToFile(url: String, suggestedFileName: String?): String =
        withContext(Dispatchers.IO) {
            val dir = File(files.userDownloadsDir())
            val rawName = suggestedFileName?.takeIf { it.isNotBlank() }
                ?: url.substringAfterLast('/').substringBefore('?').substringBefore('#')
                    .ifBlank { "asset-${UUID.randomUUID()}" }
            val safeName = rawName.substringAfterLast('/').substringAfterLast('\\')
            require(safeName.isNotBlank() && safeName != "." && safeName != "..") {
                "Invalid file name: $rawName"
            }

            val outFile = File(dir, safeName)

            if (outFile.exists()) {
                Logger.d { "Deleting existing file before download: ${outFile.absolutePath}" }
                outFile.delete()
            }

            Logger.d { "saveToFile downloading file..." }
            download(url, suggestedFileName).collect { }

            outFile.absolutePath
        }

    override suspend fun getDownloadedFilePath(fileName: String): String? =
        withContext(Dispatchers.IO) {
            val dir = File(files.userDownloadsDir())
            val file = File(dir, fileName)

            if (file.exists() && file.length() > 0) {
                file.absolutePath
            } else {
                null
            }
        }

    override suspend fun cancelDownload(fileName: String): Boolean = withContext(Dispatchers.IO) {
        val dir = File(files.userDownloadsDir())
        val file = File(dir, fileName)

        if (file.exists()) {
            val deleted = file.delete()
            if (deleted) {
                Logger.d { "Deleted file from Downloads: ${file.absolutePath}" }
            } else {
                Logger.w { "Failed to delete file: ${file.absolutePath}" }
            }
            deleted
        } else {
            false
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 8 * 1024
    }
}