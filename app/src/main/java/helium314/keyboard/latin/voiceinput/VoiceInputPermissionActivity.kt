// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voiceinput

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import helium314.keyboard.latin.R

/** Transparent activity whose only job is to request RECORD_AUDIO, since an IME service cannot request permissions directly. */
class VoiceInputPermissionActivity : ComponentActivity() {
    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        val messageRes = if (isGranted) R.string.voice_input_permission_granted else R.string.voice_input_permission_denied
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
}
