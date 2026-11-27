package com.pim.shell

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val store = ServerSettingsStore(this)
        val input = findViewById<EditText>(R.id.serverInput)
        val hint = findViewById<TextView>(R.id.hintText)
        val button = findViewById<Button>(R.id.connectButton)
        val checker = HealthChecker { HttpHealthFetcher.fetchStatus(it) }

        button.setOnClickListener {
            val normalized = ServerSettingsStore.normalize(input.text.toString())
            if (normalized == null) {
                hint.text = getString(R.string.unreachable)
                return@setOnClickListener
            }
            if (ServerSettingsStore.isRemoteCleartext(normalized)) {
                hint.text = getString(R.string.insecure_blocked)
                return@setOnClickListener
            }
            button.isEnabled = false
            hint.text = getString(R.string.connecting)
            Thread {
                val checked = checker.check(normalized)
                runOnUiThread {
                    button.isEnabled = true
                    when {
                        checked == null -> hint.text = getString(R.string.unreachable)
                        else -> enterBrowser(checked, store)
                    }
                }
            }.start()
        }
    }

    private fun enterBrowser(url: String, store: ServerSettingsStore) {
        store.saveServerUrl(url)
        startActivity(BrowserActivity.intent(this, url))
        finish()
    }
}
