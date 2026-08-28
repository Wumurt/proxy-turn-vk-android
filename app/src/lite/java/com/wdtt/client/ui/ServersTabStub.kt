package com.wdtt.client.ui

import androidx.compose.runtime.Composable

/**
 * Клиентская сборка (qWDTT Client): вкладки «Серверы» нет.
 *
 * Настоящая реализация лежит в `src/full/java/.../ui/servers/ServersTab.kt` и в
 * этот APK не попадает вовсе — вместе с админ-панелью, деплоем на VPS и jsch.
 * Заглушка существует только чтобы `MainActivity` компилировался обоими
 * флейворами; вызвать её нечем: навигация выкидывает вкладку по
 * [com.wdtt.client.BuildConfig.ADMIN_UI], который в этой сборке равен false.
 */
@Composable
fun ServersTab() = Unit
