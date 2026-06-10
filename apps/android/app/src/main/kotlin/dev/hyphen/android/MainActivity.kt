package dev.hyphen.android

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

// Plain Activity, zero external UI deps: the skeleton only proves the build
// pipeline. Compose arrives with the first UI-bearing PoC task (plan §7.2).
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "Hyphen — pre-alpha skeleton (HYP-M1-001)"
                textSize = 18f
                setPadding(48, 96, 48, 0)
            }
        )
    }
}
