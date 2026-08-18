/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.ai.edge.gallery.data.KEY_MODEL_COMMIT_HASH
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_ACCESS_TOKEN
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_ERROR_MESSAGE
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_FILE_NAME
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_MODEL_DIR
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_RATE
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_RECEIVED_BYTES
import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_REMAINING_MS
import com.google.ai.edge.gallery.data.KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES
import com.google.ai.edge.gallery.data.KEY_MODEL_EXTRA_DATA_URLS
import com.google.ai.edge.gallery.data.KEY_MODEL_IS_IMPORTED
import com.google.ai.edge.gallery.data.KEY_MODEL_IS_ZIP
import com.google.ai.edge.gallery.data.KEY_MODEL_NAME
import com.google.ai.edge.gallery.data.KEY_MODEL_START_UNZIPPING
import com.google.ai.edge.gallery.data.KEY_MODEL_TOTAL_BYTES
import com.google.ai.edge.gallery.data.KEY_MODEL_UNZIPPED_DIR
import com.google.ai.edge.gallery.data.KEY_MODEL_URL
import com.google.ai.edge.gallery.data.ModelDownloadSource
import com.google.ai.edge.gallery.data.ModelDownloadSourceStore
import com.google.ai.edge.gallery.data.ModelScopeUrlMapper
import com.google.ai.edge.gallery.data.TMP_FILE_EXT
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private const val TAG = "AGDownloadWorker"

// Number of parallel range segments used when downloading ModelScope files. Splitting the file
// into parallel segments defeats single-connection throttling and smooths out speed drops.
private const val SEGMENT_COUNT = 4

data class UrlAndFileName(val url: String, val fileName: String)

private const val FOREGROUND_NOTIFICATION_CHANNEL_ID = "model_download_channel_foreground"
private var channelCreated = false

/** A byte range (inclusive) of a file to download as one segment. */
private data class DownloadSegment(val index: Int, val start: Long, val end: Long)

class DownloadWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {
  private val externalFilesDir = context.getExternalFilesDir(null)

  private val notificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  // Unique notification id.
  private val notificationId: Int = params.id.hashCode()

  init {
    if (!channelCreated) {
      // Create a notification channel for showing notifications for model downloading progress.
      val channel =
        NotificationChannel(
            FOREGROUND_NOTIFICATION_CHANNEL_ID,
            "Model Downloading",
            // Make it silent.
            NotificationManager.IMPORTANCE_LOW,
          )
          .apply { description = "Notifications for model downloading" }
      notificationManager.createNotificationChannel(channel)
      channelCreated = true
    }
  }

  override suspend fun doWork(): Result {
    var fileUrl = inputData.getString(KEY_MODEL_URL)
    val modelName = inputData.getString(KEY_MODEL_NAME) ?: "Model"
    val version = inputData.getString(KEY_MODEL_COMMIT_HASH)!!
    val fileName = inputData.getString(KEY_MODEL_DOWNLOAD_FILE_NAME)
    val modelDir = inputData.getString(KEY_MODEL_DOWNLOAD_MODEL_DIR)!!
    val isModelImported = inputData.getBoolean(KEY_MODEL_IS_IMPORTED, false)
    val isZip = inputData.getBoolean(KEY_MODEL_IS_ZIP, false)
    val unzippedDir = inputData.getString(KEY_MODEL_UNZIPPED_DIR)
    val extraDataFileUrls = inputData.getString(KEY_MODEL_EXTRA_DATA_URLS)?.split(",") ?: listOf()
    val extraDataFileNames =
      inputData.getString(KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES)?.split(",") ?: listOf()
    val totalBytes = inputData.getLong(KEY_MODEL_TOTAL_BYTES, 0L)
    val accessToken = inputData.getString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN)

    // Rewrite download URLs when the user has chosen the ModelScope (魔搭社区) download source.
    // Unmirrored models keep their original (Hugging Face) URL automatically.
    if (ModelDownloadSourceStore.get(applicationContext) == ModelDownloadSource.MODELSCOPE) {
      ModelScopeUrlMapper.resolveModelScopeUrl(modelName)?.let { fileUrl = it }
    }

    return withContext(Dispatchers.IO) {
      if (fileUrl == null || fileName == null) {
        Result.failure()
      } else {
        return@withContext try {
          // Set the worker as a foreground service immediately.
          setForeground(createForegroundInfo(progress = 0, modelName = modelName))

          // Collect data for all files.
          val allFiles: MutableList<UrlAndFileName> = mutableListOf()
          allFiles.add(UrlAndFileName(url = fileUrl, fileName = fileName))
          for (index in extraDataFileUrls.indices) {
            allFiles.add(
              UrlAndFileName(url = extraDataFileUrls[index], fileName = extraDataFileNames[index])
            )
          }
          Log.d(TAG, "About to download: $allFiles")

          // Overall progress across all files (progress is reported against `totalBytes`, which is
          // the sum of every file).
          val progress = ProgressReporter(modelName = modelName, totalBytes = totalBytes)

          for (file in allFiles) {
            // Prepare output file's dir.
            val outputDir =
              if (isModelImported) {
                File(applicationContext.getExternalFilesDir(null), modelDir)
              } else {
                File(
                  applicationContext.getExternalFilesDir(null),
                  listOf(modelDir, version).joinToString(separator = File.separator),
                )
              }
            if (!outputDir.exists()) {
              outputDir.mkdirs()
            }

            // Read the tmp file and see if it is partially downloaded.
            val outputTmpFile =
              if (isModelImported) {
                File(
                  applicationContext.getExternalFilesDir(null),
                  listOf(modelDir, "${file.fileName}.$TMP_FILE_EXT")
                    .joinToString(separator = File.separator),
                )
              } else {
                File(
                  applicationContext.getExternalFilesDir(null),
                  listOf(modelDir, version, "${file.fileName}.$TMP_FILE_EXT")
                    .joinToString(separator = File.separator),
                )
              }

            val isModelScopeFile = ModelScopeUrlMapper.isModelScopeUrl(file.url)

            if (isModelScopeFile) {
              // ModelScope supports range requests and benefits from parallel segments. Probe the
              // exact file size, then download in parallel segments.
              val fileSize = probeFileSize(url = file.url, accessToken = accessToken)
              if (fileSize > 0L) {
                // A leftover single-connection tmp file is not segment-aligned; start segmented
                // download from scratch (it is fast enough thanks to parallelism).
                if (outputTmpFile.exists()) {
                  outputTmpFile.delete()
                }
                downloadSegmented(
                  file = file,
                  outputTmpFile = outputTmpFile,
                  accessToken = accessToken,
                  fileSize = fileSize,
                  progress = progress,
                )
              } else {
                Log.w(TAG, "Could not probe ModelScope file size; falling back to single connection.")
                downloadSingle(
                  file = file,
                  outputTmpFile = outputTmpFile,
                  isModelScopeFile = true,
                  accessToken = accessToken,
                  progress = progress,
                )
              }
            } else {
              downloadSingle(
                file = file,
                outputTmpFile = outputTmpFile,
                isModelScopeFile = false,
                accessToken = accessToken,
                progress = progress,
              )
            }

            // Rename the tmp file to the original file name by removing the tmp file ext.
            val originalFilePath = outputTmpFile.absolutePath.replace(".$TMP_FILE_EXT", "")
            val originalFile = File(originalFilePath)
            if (originalFile.exists()) {
              originalFile.delete()
            }
            outputTmpFile.renameTo(originalFile)
            Log.d(TAG, "Download done: ${file.fileName}")

            // Unzip if the downloaded file is a zip.
            if (isZip && unzippedDir != null) {
              setProgress(Data.Builder().putBoolean(KEY_MODEL_START_UNZIPPING, true).build())

              // Prepare target dir.
              val destDir =
                File(
                  externalFilesDir,
                  listOf(modelDir, version, unzippedDir).joinToString(File.separator),
                )
              if (!destDir.exists()) {
                destDir.mkdirs()
              }

              // Unzip.
              val unzipBuffer = ByteArray(4096)
              val zipFilePath =
                "${externalFilesDir}${File.separator}$modelDir${File.separator}$version${File.separator}${fileName}"
              val zipIn = ZipInputStream(BufferedInputStream(FileInputStream(zipFilePath)))
              var zipEntry: ZipEntry? = zipIn.nextEntry

              while (zipEntry != null) {
                val filePath = destDir.absolutePath + File.separator + zipEntry.name

                // Extract files.
                if (!zipEntry.isDirectory) {
                  // extract file
                  val bos = FileOutputStream(filePath)
                  bos.use { curBos ->
                    var len: Int
                    while (zipIn.read(unzipBuffer).also { len = it } > 0) {
                      curBos.write(unzipBuffer, 0, len)
                    }
                  }
                }
                // Create dir.
                else {
                  val dir = File(filePath)
                  dir.mkdirs()
                }

                zipIn.closeEntry()
                zipEntry = zipIn.nextEntry
              }
              zipIn.close()

              // Delete the original file.
              val zipFile = File(zipFilePath)
              zipFile.delete()
            }
          }
          Result.success()
        } catch (e: IOException) {
          Log.e(TAG, e.message, e)
          Result.failure(
            Data.Builder().putString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE, e.message).build()
          )
        }
      }
    }
  }

  /** Probes the exact size of a remote file via a `Range: bytes=0-0` request. */
  private fun probeFileSize(url: String, accessToken: String?): Long {
    return try {
      val connection = URL(url).openConnection() as HttpURLConnection
      connection.setRequestProperty("Referer", ModelScopeUrlMapper.referer())
      if (accessToken != null) {
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
      }
      connection.setRequestProperty("Range", "bytes=0-0")
      connection.connect()
      if (
        connection.responseCode != HttpURLConnection.HTTP_PARTIAL &&
          connection.responseCode != HttpURLConnection.HTTP_OK
      ) {
        connection.disconnect()
        return -1L
      }
      val contentRange = connection.getHeaderField("Content-Range")
      val size =
        if (contentRange != null) {
          contentRange.substringAfter("/").toLongOrNull() ?: -1L
        } else {
          connection.contentLength.toLong()
        }
      connection.disconnect()
      size
    } catch (e: Exception) {
      Log.e(TAG, "Failed to probe file size for $url", e)
      -1L
    }
  }

  /** Downloads a single file using parallel range segments (ModelScope). */
  private suspend fun downloadSegmented(
    file: UrlAndFileName,
    outputTmpFile: File,
    accessToken: String?,
    fileSize: Long,
    progress: ProgressReporter,
  ) {
    val segSize = (fileSize + SEGMENT_COUNT - 1) / SEGMENT_COUNT
    val segments =
      (0 until SEGMENT_COUNT).map { i ->
        val start = i * segSize
        val end = minOf(start + segSize, fileSize) - 1
        DownloadSegment(index = i, start = start, end = end)
      }

    val segDir = File(outputTmpFile.parentFile, "${outputTmpFile.name}.parts")
    if (!segDir.exists()) {
      segDir.mkdirs()
    }

    Log.d(TAG, "Segmented download of ${file.fileName}: size=$fileSize, ${segments.size} segments")

    // Download every segment in parallel, then merge.
    coroutineScope {
      segments
        .map { seg ->
          async(Dispatchers.IO) {
            downloadSegment(
              url = file.url,
              seg = seg,
              segDir = segDir,
              accessToken = accessToken,
              progress = progress,
            )
          }
        }
        .awaitAll()
    }

    // Merge all segments into the tmp file.
    val outputStream = FileOutputStream(outputTmpFile)
    outputStream.use { fos ->
      for (seg in segments) {
        val part = File(segDir, "part_${seg.index}")
        if (part.exists()) {
          part.inputStream().use { ins -> ins.copyTo(fos, DEFAULT_BUFFER_SIZE) }
        }
      }
    }
    segDir.deleteRecursively()
    Log.d(TAG, "Segmented download of ${file.fileName} merged")
  }

  /** Downloads one byte range to its part file, resuming if the part file already exists. */
  private suspend fun downloadSegment(
    url: String,
    seg: DownloadSegment,
    segDir: File,
    accessToken: String?,
    progress: ProgressReporter,
  ) {
    val partFile = File(segDir, "part_${seg.index}")
    val start = seg.start + partFile.length()
    // Segment already fully downloaded (e.g. resumed from a previous run).
    if (start > seg.end) {
      progress.downloadedBytes.addAndGet(seg.end - seg.start + 1)
      return
    }

    val connection = URL(url).openConnection() as HttpURLConnection
    connection.setRequestProperty("Referer", ModelScopeUrlMapper.referer())
    if (accessToken != null) {
      connection.setRequestProperty("Authorization", "Bearer $accessToken")
    }
    connection.setRequestProperty("Range", "bytes=$start-${seg.end}")
    // Force the server to send non-compressed data to make range downloads work.
    connection.setRequestProperty("Accept-Encoding", "identity")
    connection.connect()

    if (
      connection.responseCode != HttpURLConnection.HTTP_PARTIAL &&
        connection.responseCode != HttpURLConnection.HTTP_OK
    ) {
      connection.disconnect()
      throw IOException("HTTP error code: ${connection.responseCode} for segment ${seg.index}")
    }

    val inputStream = connection.inputStream
    val outputStream = RandomAccessFile(partFile, "rw")
    outputStream.seek(partFile.length())
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var bytesRead: Int
    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
      outputStream.write(buffer, 0, bytesRead)
      progress.report(bytesRead)
    }
    outputStream.close()
    inputStream.close()
    connection.disconnect()
  }

  /** Downloads a single file over a single HTTP connection (non-ModelScope source). */
  private suspend fun downloadSingle(
    file: UrlAndFileName,
    outputTmpFile: File,
    isModelScopeFile: Boolean,
    accessToken: String?,
    progress: ProgressReporter,
  ) {
    val connection = URL(file.url).openConnection() as HttpURLConnection
    if (isModelScopeFile) {
      // ModelScope requires the site referer to serve range/resumable downloads.
      connection.setRequestProperty("Referer", ModelScopeUrlMapper.referer())
    } else if (accessToken != null) {
      Log.d(TAG, "Using access token: ${accessToken.subSequence(0, 10)}...")
      connection.setRequestProperty("Authorization", "Bearer $accessToken")
    }

    val outputFileBytes = outputTmpFile.length()
    if (outputFileBytes > 0) {
      Log.d(
        TAG,
        "File '${outputTmpFile.name}' partial size: ${outputFileBytes}. Trying to resume download",
      )
      connection.setRequestProperty("Range", "bytes=${outputFileBytes}-")
      // Force the server to send non-compressed data to make download resuming work.
      connection.setRequestProperty("Accept-Encoding", "identity")
      // Count the already-downloaded bytes towards the overall progress.
      progress.downloadedBytes.addAndGet(outputFileBytes)
    }
    connection.connect()
    Log.d(TAG, "response code: ${connection.responseCode}")

    if (
      connection.responseCode == HttpURLConnection.HTTP_OK ||
        connection.responseCode == HttpURLConnection.HTTP_PARTIAL
    ) {
      val contentRange = connection.getHeaderField("Content-Range")
      if (contentRange != null) {
        val rangeParts = contentRange.substringAfter("bytes ").split("/")
        val byteRange = rangeParts[0].split("-")
        val startByte = byteRange[0].toLong()
        Log.d(TAG, "Content-Range: $contentRange. Start bytes: $startByte")
      } else {
        Log.d(TAG, "Download starts from beginning.")
      }
    } else {
      connection.disconnect()
      throw IOException("HTTP error code: ${connection.responseCode}")
    }

    val inputStream = connection.inputStream
    val outputStream = FileOutputStream(outputTmpFile, true /* append */)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var bytesRead: Int
    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
      outputStream.write(buffer, 0, bytesRead)
      progress.report(bytesRead)
    }
    outputStream.close()
    inputStream.close()
    connection.disconnect()
  }

  override suspend fun getForegroundInfo(): ForegroundInfo {
    // Initial progress is 0
    return createForegroundInfo(0)
  }

  /**
   * Thread-safe overall download progress tracker that throttles UI/notification updates to at most
   * one per ~200ms. Works for both single-connection and parallel-segment downloads.
   */
  private inner class ProgressReporter(
    private val modelName: String,
    private val totalBytes: Long,
  ) {
    val downloadedBytes = AtomicLong(0L)

    @Volatile private var lastSetProgressTs: Long = 0L
    private val startTime: Long = System.currentTimeMillis()

    suspend fun report(bytes: Int) {
      downloadedBytes.addAndGet(bytes.toLong())
      val curDownloaded = downloadedBytes.get()
      val curTs = System.currentTimeMillis()
      if (curTs - lastSetProgressTs <= 200) {
        return
      }
      lastSetProgressTs = curTs

      // Average rate since the download started (safe under concurrency).
      val elapsedMs = curTs - startTime
      val bytesPerSecond = if (elapsedMs > 0L) (curDownloaded * 1000L) / elapsedMs else 0L
      val remainingMs =
        if (bytesPerSecond > 0L && totalBytes > 0L) {
          ((totalBytes - curDownloaded) * 1000L) / bytesPerSecond
        } else {
          0L
        }

      setProgress(
        Data.Builder()
          .putLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, curDownloaded)
          .putLong(KEY_MODEL_DOWNLOAD_RATE, bytesPerSecond)
          .putLong(KEY_MODEL_DOWNLOAD_REMAINING_MS, remainingMs)
          .build()
      )
      setForeground(
        createForegroundInfo(
          progress = if (totalBytes > 0L) (curDownloaded * 100 / totalBytes).toInt() else 0,
          modelName = modelName,
        )
      )
      Log.d(TAG, "downloadedBytes: $curDownloaded")
    }
  }

  /**
   * Creates a [ForegroundInfo] object for the download worker's ongoing notification. This
   * notification is used to keep the worker running in the foreground, indicating to the user that
   * an active download is in progress.
   */
  private fun createForegroundInfo(progress: Int, modelName: String? = null): ForegroundInfo {
    // Create a notification for the foreground service
    var title = "Downloading model"
    if (modelName != null) {
      title = "Downloading \"$modelName\""
    }
    val content = "Downloading in progress: $progress%"

    val intent =
      Intent(applicationContext, Class.forName("com.google.ai.edge.gallery.MainActivity")).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
      }
    val pendingIntent =
      PendingIntent.getActivity(
        applicationContext,
        0,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    val notification =
      NotificationCompat.Builder(applicationContext, FOREGROUND_NOTIFICATION_CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(content)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true) // Makes the notification non-dismissable
        .setProgress(100, progress, false) // Show progress
        .setContentIntent(pendingIntent)
        .build()

    return ForegroundInfo(
      notificationId,
      notification,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
  }
}
