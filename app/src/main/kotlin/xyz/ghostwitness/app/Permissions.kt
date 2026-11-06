package xyz.ghostwitness.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Runtime permissions, by role and Android version. */
object Permissions {
    val capture: List<String> = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    val mesh: List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    fun missing(context: Context, wanted: List<String>): List<String> =
        wanted.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
}
