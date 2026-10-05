package org.metalmind.wireplug.net

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android 17 (API 37) blocks traffic to local-network addresses for apps
 * targeting it unless they hold ACCESS_LOCAL_NETWORK, a runtime permission.
 * DNS to the network's own servers is exempt, so the home check passes
 * without it, but direct connections to the LAN time out.
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    val required: Boolean get() = Build.VERSION.SDK_INT >= 37

    fun isAllowed(context: Context): Boolean =
        !required || context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
}
