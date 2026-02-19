package de.adv990de.obd2.bluetooth

import de.adv990de.obd2.obd.ElmController
import java.io.Closeable

interface ObdConnection : Closeable {
    suspend fun connect(): ElmController
    val isConnected: Boolean
}
