package tk.zwander.lockscreenwidgets.activities

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import tk.zwander.lockscreenwidgets.services.Accessibility

/**
 * Used when the lock screen needs to be dismissed.
 * This is either started when the add button is tapped from the lock screen
 * or when Lockscreen Widgets detects an Activity being launched from a widget.
 */
class RequestUnlockActivity : AppCompatActivity() {
    private val kgm by lazy { getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    private val dismissListener = object : Accessibility.OnLockscreenDismissListener() {
        override fun onDismissed() {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            kgm.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissCancelled() {
                    finish()
                }

                override fun onDismissError() {
                    finish()
                }

                override fun onDismissSucceeded() {
                    finish()
                }
            })
        } else {
            //If we're below 8.0, we have to do some weirdness to dismiss the lock screen.
            dismissListener.register(this)
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        dismissListener.unregister(this)
    }
}