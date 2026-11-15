package com.kimios.frontend

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object AssetInstaller {
    private const val TAG = "KimiOS-Assets"

    fun install(context: Context): File? {
        val filesDir = context.filesDir
        copyTree(context, "kimios", File(filesDir, "kimios"))
        copyTree(context, "qemu", File(filesDir, "qemu"))

        val binary = File(context.applicationInfo.nativeLibraryDir, "libqemu.so")
        if (!binary.exists()) {
            Log.e(TAG, "libqemu.so missing from jniLibs (run scripts/install-qemu.sh)")
            return null
        }
        return binary
    }

    private fun copyTree(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            try {
                target.parentFile?.mkdirs()
                copyFile(context, assetPath, target)
            } catch (_: java.io.FileNotFoundException) {
                target.mkdirs()
            }
            return
        }
        target.mkdirs()
        for (child in children) {
            copyTree(context, "$assetPath/$child", File(target, child))
        }
    }

    private fun copyFile(context: Context, assetPath: String, target: File) {
        if (assetPath.endsWith("disk.img") && target.exists()) {
            return
        }
        context.assets.open(assetPath).use { input ->
            if (target.exists() && target.length() == input.available().toLong()) {
                return
            }
            FileOutputStream(target).use { output ->
                input.copyTo(output, 1 shl 16)
            }
        }
        if (assetPath.endsWith(".so") || assetPath.contains("qemu-system")) {
            try {
                Os.chmod(target.absolutePath, 0b111_101_101)
            } catch (_: Exception) {
            }
        }
    }
}
