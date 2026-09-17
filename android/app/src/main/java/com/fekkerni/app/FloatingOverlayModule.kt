package com.fekkerni.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

class FloatingOverlayModule(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String = "FloatingOverlay"

  @ReactMethod
  fun hasOverlayPermission(promise: Promise) {
    promise.resolve(Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(reactContext))
  }

  @ReactMethod
  fun openOverlaySettings() {
    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
      data = Uri.parse("package:${reactContext.packageName}")
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    reactContext.startActivity(intent)
  }

  @ReactMethod
  fun startOverlay(promise: Promise) {
    try {
      val intent = Intent(reactContext, FloatingOverlayService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        reactContext.startForegroundService(intent)
      } else {
        reactContext.startService(intent)
      }
      promise.resolve(true)
    } catch (error: Exception) {
      promise.reject("OVERLAY_START_FAILED", error)
    }
  }

  @ReactMethod
  fun stopOverlay(promise: Promise) {
    promise.resolve(reactContext.stopService(Intent(reactContext, FloatingOverlayService::class.java)))
  }
}