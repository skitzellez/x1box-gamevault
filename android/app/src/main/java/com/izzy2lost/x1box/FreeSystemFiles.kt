// Copyright (c) 2026 Skitzelles - part of Game Vault, https://github.com/skitzellez/xbox-game-vault
// Licensed under the GNU GPL version 2 or (at your option) any later version, like the rest of xemu.

package com.izzy2lost.x1box

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/*
 * Zero setup for Game Vault: when the player has not given a BIOS or a hard
 * disk of their own, the free SpiritBoot BIOS (GPL-2.0, assets/gamevault) and
 * a blank hard disk are used, so a game sent from Game Vault starts right away.
 * The player's own MCPX + flash always win. SpiritBoot needs no MCPX ROM.
 */
object FreeSystemFiles {
  private const val TAG = "FreeSystemFiles"
  private const val ASSET_DIR = "gamevault"
  private const val BLANK_HDD_ASSET = "xbox_hdd_blank.qcow2"
  private const val FREE_HDD_NAME = "hdd_free.qcow2"

  private fun dir(context: Context): File {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    return File(base, "x1box/gamevault")
  }

  private fun flashAsset(prefs: SharedPreferences): String =
    if (prefs.getInt("setting_system_memory_mib", 64) == 128) {
      "SpiritBoot-v1.0.0-128MB.bin"
    } else {
      "SpiritBoot-v1.0.0-64MB.bin"
    }

  private fun isOurs(context: Context, path: String?): Boolean =
    path != null && File(path).parentFile?.absolutePath == dir(context).absolutePath

  /* True when the free BIOS is in use: the missing MCPX is then not needed. */
  fun isFreeBios(context: Context, prefs: SharedPreferences): Boolean {
    val flash = prefs.getString("flashPath", null) ?: return false
    return isOurs(context, flash) && File(flash).isFile
  }

  /*
   * Fills in what is missing. Cheap after the first run (the files are only
   * copied when absent). Commits, because the emulator runs in another process.
   */
  fun ensure(context: Context) {
    val prefs = context.getSharedPreferences("x1box_prefs", Context.MODE_PRIVATE)
    val editor = prefs.edit()
    var changed = false
    val freeBefore = isFreeBios(context, prefs)

    val ownMcpx = isAccessible(context, prefs, "mcpxPath", "mcpxUri")
    val ownFlash = !freeBefore && isAccessible(context, prefs, "flashPath", "flashUri")

    // A Microsoft BIOS without its MCPX ROM cannot boot, so the free one is used then too.
    if (!(ownMcpx && ownFlash)) {
      val name = flashAsset(prefs)
      val target = File(dir(context), name)
      if (copyAsset(context, "$ASSET_DIR/$name", target)) {
        if (prefs.getString("flashPath", null) != target.absolutePath) {
          DebugLog.i(TAG) { "Using the free BIOS $name (own MCPX=$ownMcpx, own flash=$ownFlash)" }
          editor.putString("flashPath", target.absolutePath).remove("flashUri")
          changed = true
        }
      }
    }

    if (!isAccessible(context, prefs, "hddPath", "hddUri")) {
      val target = File(dir(context), FREE_HDD_NAME)
      if (target.isFile || copyAsset(context, "$ASSET_DIR/$BLANK_HDD_ASSET", target)) {
        DebugLog.i(TAG) { "Using the blank hard disk ${target.absolutePath}" }
        editor.putString("hddPath", target.absolutePath).remove("hddUri")
        changed = true
      }
    }

    if (changed) {
      editor.commit()
    }
  }

  private fun isAccessible(
    context: Context,
    prefs: SharedPreferences,
    pathKey: String,
    uriKey: String,
  ): Boolean {
    val path = prefs.getString(pathKey, null)
    if (!path.isNullOrBlank() && File(path).isFile) {
      return true
    }
    val uri = prefs.getString(uriKey, null)?.let(Uri::parse) ?: return false
    return context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
  }

  private fun copyAsset(context: Context, asset: String, target: File): Boolean {
    if (target.isFile && target.length() > 0) {
      return true
    }
    val parent = target.parentFile
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
      DebugLog.e(TAG) { "Cannot create ${parent.absolutePath}" }
      return false
    }
    val temp = File(target.absolutePath + ".part")
    return try {
      context.assets.open(asset).use { input ->
        FileOutputStream(temp).use { output -> input.copyTo(output) }
      }
      temp.renameTo(target)
    } catch (e: IOException) {
      DebugLog.e(TAG, e) { "Copy of $asset failed" }
      temp.delete()
      false
    }
  }
}
