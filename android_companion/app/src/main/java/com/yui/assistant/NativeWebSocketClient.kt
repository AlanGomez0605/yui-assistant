package com.yui.assistant

import android.util.Log
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64
import javax.net.ssl.SSLSocketFactory

/**
 * NativeWebSocketClient — Cliente WebSocket mínimo nativo en Java/Kotlin.
 * No requiere librerías externas (OkHttp, etc.).
 * Implementa el handshake HTTP Upgrade básico de RFC 6455.
 *
 * Solo necesario porque YuiReminderSyncService corre en un contexto
 * donde no queremos añadir dependencias de red pesadas.
 */
class NativeWebSocketClient(
    private val uri: URI,
    private val apiToken: String = "",
    private val onMessage: (String) -> Unit
) {
    companion object {
        private const val TAG = "NativeWS"
    }

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var isConnected = false

    val isOpen: Boolean get() = isConnected && socket?.isConnected == true

    fun connect() {
        val host = uri.host
        val port = if (uri.port == -1) {
            if (uri.scheme == "wss") 443 else 80
        } else uri.port
        val path = if (uri.path.isNullOrEmpty()) "/" else uri.path
        val useSSL = uri.scheme == "wss"

        socket = if (useSSL) {
            SSLSocketFactory.getDefault().createSocket(host, port)
        } else {
            Socket(host, port)
        }

        outputStream = socket!!.getOutputStream()
        val inputStream = socket!!.getInputStream()

        // Generar Sec-WebSocket-Key aleatorio
        val random = ByteArray(16)
        SecureRandom().nextBytes(random)
        val key = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Base64.getEncoder().encodeToString(random)
        } else {
            android.util.Base64.encodeToString(random, android.util.Base64.NO_WRAP)
        }

        // Enviar HTTP Upgrade handshake
        val handshake = buildString {
            append("GET $path HTTP/1.1\r\n")
            append("Host: $host:$port\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $key\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            if (apiToken.isNotBlank()) append("Authorization: Bearer $apiToken\r\n")
            append("\r\n")
        }
        outputStream!!.write(handshake.toByteArray(Charsets.UTF_8))
        outputStream!!.flush()

        // Leer respuesta HTTP del servidor
        val responseHeaders = readHttpHeaders(inputStream)
        val headerLines = responseHeaders.split("\r\n")
        val responseLine = headerLines.firstOrNull() ?: throw Exception("Sin respuesta del servidor WebSocket")
        if (!responseLine.contains("101")) {
            throw Exception("WebSocket handshake fallido: $responseLine")
        }

        val acceptHeader = headerLines.firstOrNull {
            it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true)
        }?.substringAfter(":")?.trim()

        val expectedAccept = base64(
            MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))
        )
        if (acceptHeader != expectedAccept) {
            socket?.close()
            throw Exception("Respuesta WebSocket no valida")
        }

        isConnected = true
        Log.d(TAG, "WebSocket conectado a $uri")

        // Leer frames en un hilo separado
        Thread {
            try {
                val rawInput = socket!!.getInputStream()
                while (isConnected) {
                    val frame = readFrame(rawInput) ?: break
                    if (frame.isNotBlank()) {
                        onMessage(frame)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "WS lectura interrumpida: ${e.message}")
            } finally {
                isConnected = false
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun base64(bytes: ByteArray): String {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Base64.getEncoder().encodeToString(bytes)
        } else {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
    }

    private fun readHttpHeaders(input: java.io.InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        var matched = 0
        val delimiter = byteArrayOf(13, 10, 13, 10)
        while (bytes.size() < 16 * 1024) {
            val value = input.read()
            if (value == -1) throw Exception("Respuesta HTTP incompleta")
            bytes.write(value)
            matched = if (value.toByte() == delimiter[matched]) matched + 1 else if (value == 13) 1 else 0
            if (matched == delimiter.size) return bytes.toString(Charsets.US_ASCII.name())
        }
        throw Exception("Cabeceras WebSocket demasiado grandes")
    }

    fun close() {
        isConnected = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    fun send(text: String) {
        try {
            val data = text.toByteArray(Charsets.UTF_8)
            val frame = buildWebSocketFrame(data)
            outputStream?.write(frame)
            outputStream?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Error enviando frame WS: ${e.message}")
            isConnected = false
        }
    }

    private fun buildWebSocketFrame(payload: ByteArray): ByteArray {
        val length = payload.size
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val masked = ByteArray(length) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }

        val header = mutableListOf<Byte>()
        header.add(0x81.toByte())  // FIN=1, opcode=1 (text)
        when {
            length < 126 -> header.add((length or 0x80).toByte())
            length < 65536 -> {
                header.add((126 or 0x80).toByte())
                header.add((length shr 8).toByte())
                header.add((length and 0xFF).toByte())
            }
            else -> {
                header.add((127 or 0x80).toByte())
                for (shift in 56 downTo 0 step 8) header.add((length.toLong() shr shift and 0xFF).toByte())
            }
        }
        header.addAll(mask.toList())
        header.addAll(masked.toList())
        return header.toByteArray()
    }

    private fun readFrame(input: java.io.InputStream): String? {
        return try {
            val b0 = input.read()
            val b1 = input.read()
            if (b0 == -1 || b1 == -1) return null

            val opcode = b0 and 0x0F
            if (opcode == 8) { isConnected = false; return null }  // connection close
            if (opcode != 1) {
                // Leer y descartar frames no-texto
                val len = (b1 and 0x7F).toLong()
                input.skip(len)
                return ""
            }

            var length = (b1 and 0x7F).toLong()
            if (length == 126L) {
                length = ((input.read() shl 8) or input.read()).toLong()
            } else if (length == 127L) {
                length = 0L
                for (i in 0 until 8) length = (length shl 8) or input.read().toLong()
            }

            val payload = ByteArray(length.toInt())
            var read = 0
            while (read < length) {
                val r = input.read(payload, read, (length - read).toInt())
                if (r == -1) break
                read += r
            }
            String(payload, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
