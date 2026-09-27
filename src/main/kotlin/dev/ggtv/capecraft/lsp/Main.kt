package dev.ggtv.capecraft.lsp

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream

/**
 * Точка входа сервера.
 *
 * ## Почему протокол и журнал разведены
 *
 * stdout — это **канал протокола**, и он достаётся серверу отдельным потоком,
 * который открывается мимо `System.out`. Логи уходят в stderr. Если писать в
 * `System.out`, то клиент вместо заголовка `Content-Length` получит строку
 * журнала и отвалится молча, без единого слова в своём логе: самое
 * неприятное место для отладки.
 *
 * Свой `FileOutputStream(FileDescriptor.out)` нужен ещё и потому, что
 * `System.out` — обёртка с буфером и блокировкой, а кадры протокола писать
 * надо сразу и без перемешивания с логами.
 */
fun main() {
    val protocol = FileOutputStream(FileDescriptor.out)
    val log: PrintStream = System.err

    val code = try {
        LspServer(System.`in`, protocol, log::println).use { it.run() }
    } catch (e: Exception) {
        // Ранний сбой (например, stdin уже закрыт) должен быть виден в
        // журнале редактора, а не потеряться.
        log.println("[capecraft-lsp] не запустился: $e")
        1
    }
    log.flush()
    protocol.flush()
    kotlin.system.exitProcess(code)
}
