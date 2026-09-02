package com.wdtt.client

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Серверный бинарник, который деплой заливает на VPS.
 *
 * Раньше он ехал внутри APK, в assets: 13 МБ статического линуксового ELF.
 * Android-приложение, которое носит в себе исполняемый файл для другой ОС и
 * раскладывает его по чужим машинам, для антивирусных эвристик выглядит как
 * дроппер — отсюда ложные срабатывания на публикуемых сборках. Теперь файл
 * лежит отдельным файлом релиза и скачивается по требованию.
 *
 * Раз файл приходит из сети и запускается на сервере пользователя с правами
 * root, доверять ему «потому что скачан с github.com» нельзя. Поэтому сверяется
 * SHA-256, вшитая в APK при сборке ([BuildConfig.SERVER_SHA256]): CI считает её
 * от того же файла, который публикует. Сборка без вшитой суммы деплоить
 * отказывается — молча качать неизвестный исполняемый файл хуже, чем не уметь
 * деплоить вовсе.
 */
object ServerBinary {

    class Failure(message: String) : Exception(message)

    private const val BUFFER = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val CACHE_DIR = "server-bin"

    fun assetName(version: String): String = "wdtt-server-$version-linux-amd64"

    /** Адрес файла в релизе; [BuildConfig.SERVER_URL] позволяет подменить его при отладке. */
    fun downloadUrl(version: String): String {
        val override = BuildConfig.SERVER_URL
        if (override.isNotBlank()) return override
        return "https://github.com/${AppLinks.GITHUB_REPO}/releases/download/v$version/${assetName(version)}"
    }

    /**
     * Отдаёт локальный файл сервера, при необходимости скачав его.
     *
     * Файл кэшируется под именем своей контрольной суммы, поэтому повторные
     * деплои не тянут 13 МБ заново. Возвращённый файл удалять не нужно — он и
     * есть кэш.
     */
    fun obtain(context: Context, onProgress: (Float, String) -> Unit): File {
        val expected = BuildConfig.SERVER_SHA256.lowercase(Locale.ROOT)
        if (!expected.matches(Regex("[0-9a-f]{64}"))) {
            throw Failure(
                "В этой сборке не зашита контрольная сумма сервера, деплой недоступен. " +
                    "Используйте релизный APK или соберите с -PserverSha256=<sha256>."
            )
        }

        val cacheDir = File(context.filesDir, CACHE_DIR)
        cacheDir.mkdirs()
        val cached = File(cacheDir, "$expected.bin")
        if (cached.isFile && sha256(cached) == expected) {
            onProgress(1f, "Сервер уже загружен")
            return cached
        }

        // Осталось от прошлых версий приложения — своей суммы у них уже нет.
        cacheDir.listFiles()?.forEach { it.delete() }

        val url = downloadUrl(BuildConfig.VERSION_NAME)
        val tmp = File(cacheDir, "download.tmp")
        try {
            download(url, tmp, onProgress)
            val actual = sha256(tmp)
            if (actual != expected) {
                throw Failure(
                    "Контрольная сумма скачанного сервера не совпала — файл не будет использован.\n" +
                        "ожидалась: $expected\nполучена: $actual"
                )
            }
            if (!tmp.renameTo(cached)) {
                throw Failure("Не удалось сохранить файл сервера в кэш")
            }
            return cached
        } finally {
            tmp.delete()
        }
    }

    private fun download(url: String, target: File, onProgress: (Float, String) -> Unit) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "qWDTTAndroid/${BuildConfig.VERSION_NAME}")
            }
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw Failure(
                    "Серверный файл для версии ${BuildConfig.VERSION_NAME} не найден в релизе.\n$url"
                )
            }
            if (code !in 200..299) {
                throw Failure("Сервер вернул HTTP $code при загрузке файла сервера")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    copyWithProgress(input, output, total, onProgress)
                }
            }
        } catch (e: Failure) {
            throw e
        } catch (e: Exception) {
            throw Failure("Не удалось скачать серверный файл: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun copyWithProgress(
        input: InputStream,
        output: FileOutputStream,
        total: Long,
        onProgress: (Float, String) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER)
        var copied = 0L
        var lastReported = -1
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            copied += read
            if (total > 0) {
                val percent = (copied * 100 / total).toInt()
                if (percent != lastReported) {
                    lastReported = percent
                    onProgress(percent / 100f, "Загрузка сервера… $percent%")
                }
            }
        }
        if (total > 0 && copied != total) {
            throw Failure("Файл сервера скачан не полностью: $copied из $total байт")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
