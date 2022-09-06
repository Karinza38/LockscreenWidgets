package tk.zwander.lockscreenwidgets.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

val Context.hasStoragePermission: Boolean
    get() = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            checkCallingOrSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
            checkCallingOrSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        else -> true
    }