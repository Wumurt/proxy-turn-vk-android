package com.wdtt.client

/**
 * Внешние ссылки приложения — единственное место, где они задаются.
 *
 * Пустое значение означает «ссылки нет»: блок интерфейса, который её показывал,
 * не отрисовывается вовсе. Чтобы вернуть блок, достаточно вписать сюда адрес —
 * подписи кнопок выводятся из самого адреса, экраны править не нужно.
 */
object AppLinks {
    /** Telegram-канал проекта. Пусто — блоки со ссылкой на канал скрыты. */
    const val TELEGRAM_CHANNEL = ""

    /** Telegram-боты с готовыми конфигами. Пусто — блок «Где взять конфиги» скрыт. */
    val CONFIG_BOTS: List<String> = emptyList()

    /** Страница доната. Пусто — кнопки поддержки и окно с просьбой о ней скрыты. */
    const val DONATE_URL = ""

    val hasTelegramChannel: Boolean get() = TELEGRAM_CHANNEL.isNotBlank()
    val hasConfigBots: Boolean get() = CONFIG_BOTS.isNotEmpty()
    val hasDonate: Boolean get() = DONATE_URL.isNotBlank()

    /** `https://t.me/foo` → `@foo`; для остальных адресов возвращает сам адрес. */
    fun handle(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val name = trimmed.substringAfterLast('/')
        return if (trimmed.startsWith("https://t.me/", ignoreCase = true) && name.isNotEmpty()) {
            "@$name"
        } else {
            trimmed
        }
    }
}
