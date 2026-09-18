package com.yui.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

object OfflineCommandEngine {

    private const val TAG = "OfflineEngine"
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    fun initTts(context: Context) {
        if (tts == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = tts?.setLanguage(Locale("es", "ES"))
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale.getDefault())
                    }
                    isTtsReady = true
                }
            }
        }
    }

    fun speak(context: Context, text: String) {
        initTts(context)
        if (isTtsReady && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "YuiOfflineTTS")
        }
    }

    data class CommandResult(
        val handled: Boolean,
        val responseMessage: String,
        val actionType: String? = null
    )

    fun processCommand(context: Context, input: String): CommandResult {
        val query = input.trim().lowercase(Locale.ROOT)

        // 1. Alarma / Despertador
        if (query.contains("alarma") || query.contains("despiértame") || query.contains("despiertame")) {
            val hourMinute = extractTime(query)
            if (hourMinute != null) {
                val (hour, minute) = hourMinute
                val message = "Alarma programada por Yui"
                val success = DeviceControlManager.setAlarm(context, hour, minute, message)
                val reply = if (success) {
                    "He programado tu alarma para las %02d:%02d, Alan.".format(hour, minute)
                } else {
                    "Intenté programar la alarma para las %02d:%02d pero hubo un problema.".format(hour, minute)
                }
                speak(context, reply)
                return CommandResult(true, reply, "SET_ALARM")
            }
        }

        // 2. Recordatorios locales y autónomos
        if (query.contains("recuérdame") || query.contains("recuerdame") || query.contains("recordatorio")) {
            val title = query.replace("recuérdame", "")
                .replace("recuerdame", "")
                .replace("crea un recordatorio", "")
                .replace("pon un recordatorio", "")
                .replace("recordatorio", "")
                .trim()

            val hourMinute = extractTime(query)
            val cal = Calendar.getInstance()
            if (hourMinute != null) {
                cal.set(Calendar.HOUR_OF_DAY, hourMinute.first)
                cal.set(Calendar.MINUTE, hourMinute.second)
                cal.set(Calendar.SECOND, 0)
                if (cal.timeInMillis <= System.currentTimeMillis()) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
            } else {
                cal.add(Calendar.HOUR_OF_DAY, 1)
            }

            val cleanTitle = if (title.isNotBlank()) title.replaceFirstChar { it.uppercase() } else "Recordatorio importante"
            AutonomousReminderManager.scheduleLocalReminder(context, cleanTitle, cal.timeInMillis, "Recordatorio programado por comando")
            
            // Encolar para sincronización en la nube cuando vuelva internet
            OfflineSyncQueue.enqueueTask(context, "REMINDER_CREATE", mapOf(
                "title" to cleanTitle,
                "due_datetime" to "%04d-%02d-%02d %02d:%02d:00".format(
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
                )
            ))

            val reply = "Anotado. Te lo recordaré de forma autónoma a las %02d:%02d.".format(
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
            )
            speak(context, reply)
            return CommandResult(true, reply, "CREATE_REMINDER")
        }

        // 3. Enviar WhatsApp
        if (query.contains("whatsapp") || query.contains("mensaje")) {
            val phoneOrName = extractTargetContact(query)
            val messageText = extractMessageBody(query)
            if (phoneOrName.isNotEmpty() && messageText.isNotEmpty()) {
                val success = DeviceControlManager.sendWhatsApp(context, phoneOrName, messageText)
                val reply = if (success) "Abriendo WhatsApp para enviar tu mensaje a $phoneOrName." else "No pude preparar el mensaje de WhatsApp."
                speak(context, reply)
                return CommandResult(true, reply, "SEND_WHATSAPP")
            }
        }

        // 4. Abrir Aplicaciones
        if (query.startsWith("abre ") || query.startsWith("abrir ") || query.startsWith("inicia ") || query.startsWith("lanzar ")) {
            val appTarget = query.replace("abre ", "")
                .replace("abrir ", "")
                .replace("inicia ", "")
                .replace("lanzar ", "")
                .replace("la app ", "")
                .replace("la aplicación ", "")
                .trim()

            if (appTarget.isNotEmpty()) {
                val opened = DeviceControlManager.openApp(context, appTarget)
                val reply = if (opened) "Abriendo $appTarget." else "No encontré la aplicación $appTarget instalada."
                speak(context, reply)
                return CommandResult(true, reply, "OPEN_APP")
            }
        }

        // 5. Llamadas
        if (query.startsWith("llama a ") || query.startsWith("llamar a ") || query.startsWith("marcar a ")) {
            val target = query.replace("llama a ", "").replace("llamar a ", "").replace("marcar a ", "").trim()
            if (target.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$target"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                val reply = "Iniciando llamada a $target."
                speak(context, reply)
                return CommandResult(true, reply, "CALL")
            }
        }

        // 6. Consultas de Hora / Fecha Offline
        if (query.contains("hora es") || query.contains("qué hora") || query.contains("la hora")) {
            val now = Calendar.getInstance()
            val reply = "Son las %02d:%02d, Alan.".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
            speak(context, reply)
            return CommandResult(true, reply, "TIME_QUERY")
        }

        return CommandResult(false, "No pude procesar el comando sin conexión.")
    }

    private fun extractTime(text: String): Pair<Int, Int>? {
        val pattern = Pattern.compile("(\\d{1,2})[:\\s](\\d{2})|a las (\\d{1,2})")
        val matcher = pattern.matcher(text)
        if (matcher.find()) {
            if (matcher.group(1) != null && matcher.group(2) != null) {
                var hour = matcher.group(1)!!.toInt()
                val minute = matcher.group(2)!!.toInt()
                if (text.contains("pm") || text.contains("tarde") || text.contains("noche")) {
                    if (hour < 12) hour += 12
                }
                return Pair(hour, minute)
            } else if (matcher.group(3) != null) {
                var hour = matcher.group(3)!!.toInt()
                var minute = 0
                if (text.contains("media")) minute = 30
                if (text.contains("cuarto")) minute = 15
                if (text.contains("pm") || text.contains("tarde") || text.contains("noche")) {
                    if (hour < 12) hour += 12
                }
                return Pair(hour, minute)
            }
        }
        return null
    }

    private fun extractTargetContact(text: String): String {
        val match = Pattern.compile("(?:a|para)\\s+([a-zA-Z0-9_+]+)").matcher(text)
        return if (match.find()) match.group(1) ?: "" else ""
    }

    private fun extractMessageBody(text: String): String {
        val markers = listOf("diciendo", "que diga", "mensaje", "texto")
        for (m in markers) {
            val idx = text.indexOf(m)
            if (idx != -1) {
                return text.substring(idx + m.length).trim()
            }
        }
        return "Hola"
    }
}
