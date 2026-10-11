package com.auralis.service.scanner

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView

class ScannerTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply {
            text = "Auralis scanner verification\n\nChecking synthetic audio files. Your music is unchanged."
            textSize = 22f
            setPadding(32, 100, 32, 32)
        })
    }
}
