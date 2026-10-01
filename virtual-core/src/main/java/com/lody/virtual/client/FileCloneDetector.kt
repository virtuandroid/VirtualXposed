package com.lody.virtual.client

import com.lody.virtual.os.VEnvironment
import com.lody.virtual.os.VUserHandle
import com.virtualxposed.log.client.LogMessage
import com.virtualxposed.log.client.VLoggingClient
import timber.log.Timber
import java.io.File
import java.util.Arrays
import kotlin.time.measureTime

class FileCloneDetector {
    private fun File.listAllFiles(): List<File> {
        if (!this.isDirectory) return emptyList()

        return this.walkTopDown()
            .filter { it.isFile }
            .toList()
    }

    fun areFilesIdentical(path1: File, path2: File): Boolean {
        if (path1.length() != path2.length()) {
            return false
        }

        val bufferSize = 8192
        path1.inputStream().use { stream1 ->
            path2.inputStream().use { stream2 ->
                val buffer1 = ByteArray(bufferSize)
                val buffer2 = ByteArray(bufferSize)

                while (true) {
                    val bytesRead1 = stream1.read(buffer1)
                    val bytesRead2 = stream2.read(buffer2)

                    if (bytesRead1 != bytesRead2) {
                        return false
                    }

                    // End of both files reached without mismatch
                    if (bytesRead1 == 0 || bytesRead1 == -1) {
                        return true
                    }

                    // Compare chunk contents
                    if (!Arrays.equals(buffer1, 0, bytesRead1, buffer2, 0, bytesRead2)) {
                        return false
                    }
                }
            }
        }
    }


    fun start(packageName: String) {
        measureTime {
            val systemDir = VEnvironment.getUserSystemDirectory()
            val currentDir =
                File(VEnvironment.getUserSystemDirectory(VUserHandle.myUserId()), packageName)
            if (!currentDir.exists()) {
                Timber.e("${currentDir.absolutePath} does not exist! Cannot start file clone detector.")
                return
            }

            // /data/user/0/io.va.exposed64/virtual/data/user
            val userDirectories = systemDir.listFiles() ?: return
            val filenameBucket: MutableMap<String, MutableList<File>> = mutableMapOf()

            currentDir.listAllFiles().forEach {
                filenameBucket[it.absolutePath.removePrefix(currentDir.absolutePath)] =
                    mutableListOf()
            }

            userDirectories.forEach { userDir ->
                // Do not check the same path
                val packageDir = File(userDir, packageName)
                if (packageDir.absolutePath == currentDir.absolutePath) return@forEach

                packageDir.listAllFiles().forEach {
                    filenameBucket[it.absolutePath.removePrefix(packageDir.absolutePath)]?.add(it)
                }
            }

            filenameBucket.forEach { (originalFilePath, otherFiles) ->
                if (otherFiles.isEmpty()) return@forEach
                val realPath = currentDir.absolutePath + originalFilePath
                val realFile = File(realPath)

                val identicalFiles = otherFiles.filter { otherFile ->
                    // Skip file checks above 10MB, usually not user data and time-consuming
                    if (otherFile.length() > 10L * 1024 * 1024) {
                        return@forEach
                    }

                    // No interesting data under 10 bytes
                    if (otherFile.length() < 10) {
                        return@forEach
                    }

                    // Ignore cache files
                    if (originalFilePath.startsWith("/cache/")) {
                        return
                    }

                    areFilesIdentical(realFile, otherFile)
                }

                identicalFiles.forEach { identicalFile ->
                    VLoggingClient.log(
                        LogMessage.FileIdentical(
                            realFile.absolutePath,
                            identicalFile.absolutePath
                        )
                    )
                }
            }
        }.also {
            Timber.i("Time taken to process file paths: ${it.inWholeMilliseconds}ms")
        }
    }
}