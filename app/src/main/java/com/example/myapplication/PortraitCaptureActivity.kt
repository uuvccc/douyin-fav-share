package com.example.myapplication

import android.content.pm.ActivityInfo
import android.os.Bundle
import com.journeyapps.barcodescanner.CaptureActivity

/**
 * 扫码页：锁定竖屏，扫码时屏幕不随手机旋转。
 * 由 MainActivity 的 ScanOptions.setCaptureActivity 指定。
 */
class PortraitCaptureActivity : CaptureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        super.onCreate(savedInstanceState)
    }
}
