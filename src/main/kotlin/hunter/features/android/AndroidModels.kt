package hunter.features.android

enum class AndroidConnection(val title: String) {
    Usb("USB"), Wifi("Wi-Fi"), Emulator("Emulator");

    companion object {
        fun forSerial(serial: String) = when {
            serial.startsWith("emulator-") -> Emulator
            ':' in serial || serial.contains("._adb-tls-connect._tcp") -> Wifi
            else -> Usb
        }
    }
}

data class Device(val serial: String, val state: String, val model: String = "") {
    val connection get() = AndroidConnection.forSerial(serial)

    val label
        get() = "${model.ifBlank { serial }} · $serial · $state"
}

data class AndroidApp(
    val packageName: String,
    val uid: Int?,
    val user: Int,
    val system: Boolean,
    val name: String = "",
) {
    val label
        get() = name.ifBlank { packageName }

    val appId
        get() = uid?.rem(100000)?.toString() ?: "?"

    fun matches(query: String) = "$name $packageName $uid $appId".contains(query, true)
}

data class Permission(val name: String, val granted: Boolean?)
