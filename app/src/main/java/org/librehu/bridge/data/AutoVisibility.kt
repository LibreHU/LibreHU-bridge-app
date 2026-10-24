package org.librehu.bridge.data

import android.content.Context
import android.os.Build
import java.util.concurrent.TimeUnit

/**
 * Whether Android Auto will list this app. An app built with the Car App Library (this one) is shown only when it was
 * installed from a trusted source such as Google Play: Android Auto's "Unknown sources" developer setting covers media,
 * messaging and parked apps but not Car App Library ones (developer.android.com/training/cars/testing). Android Auto
 * looks at the installer recorded for the app: installed with Google Play as installer (`pm install -i
 * com.android.vending`, adb or root), it is listed.
 */
object AutoVisibility {
    const val PLAY = "com.android.vending"

    /** Package that installed this app (null = adb or a file manager without one). */
    fun installer(context: Context): String? =
        runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= 30) {
                pm.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()

    fun visible(context: Context): Boolean = installer(context) == PLAY

    /** The adb command that installs the APK with Google Play as installer (from a computer). */
    const val ADB_COMMAND = "adb install -r -i com.android.vending LibreHU-Bridge-main.apk"

    /** Root on the phone: installs this very APK again with Google Play as installer (the app restarts). */
    fun reinstallAsPlay(context: Context): Result<Unit> =
        runCatching {
            val apk = context.applicationInfo.sourceDir
            val tmp = "/data/local/tmp/librehu-bridge.apk"
            val p =
                ProcessBuilder(
                    "su",
                    "-c",
                    "cp '$apk' $tmp && chmod 644 $tmp && pm install -r -i $PLAY $tmp; r=\$?; rm -f $tmp; exit \$r",
                ).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(120, TimeUnit.SECONDS)) error("timeout")
            if (p.exitValue() != 0 || !out.contains("Success")) error(out.trim().ifEmpty { "su refused" })
        }
}
