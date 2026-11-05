package dev.namnv.rexc.lock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Asks for the owner: fingerprint or face with the screen lock as fallback, through the framework
 * prompt (no androidx.biometric). Android 9 has no credential option in that prompt, so it confirms
 * the screen lock instead. Create it before the activity starts: it registers a result launcher.
 */
class DeviceAuth(private val activity: ComponentActivity) {
  private var pending: ((Boolean) -> Unit)? = null

  private val confirm = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
    finish(it.resultCode == Activity.RESULT_OK)
  }

  /** Runs [onResult] on the main thread once the prompt closes; true means the owner. */
  fun authenticate(title: String, onResult: (Boolean) -> Unit) {
    pending = onResult
    when {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> prompt(title) {
        setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
      }
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> prompt(title) {
        @Suppress("DEPRECATION")
        setDeviceCredentialAllowed(true)
      }
      else -> {
        val keyguard = activity.getSystemService(KeyguardManager::class.java)

        @Suppress("DEPRECATION")
        val intent = keyguard.createConfirmDeviceCredentialIntent(title, null)
        if (intent == null) finish(false) else confirm.launch(intent)
      }
    }
  }

  private fun prompt(title: String, allow: BiometricPrompt.Builder.() -> Unit) {
    BiometricPrompt.Builder(activity).setTitle(title).apply(allow).build().authenticate(
      CancellationSignal(),
      activity.mainExecutor,
      object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = finish(true)

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finish(false)
      },
    )
  }

  private fun finish(ok: Boolean) {
    val done = pending ?: return
    pending = null
    done(ok)
  }

  companion object {
    /** A screen lock is set: without one there is nothing to ask for. */
    fun available(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java).isDeviceSecure
  }
}
