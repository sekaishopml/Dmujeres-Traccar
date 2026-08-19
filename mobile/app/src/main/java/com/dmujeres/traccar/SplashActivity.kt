package com.dmujeres.traccar

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.databinding.ActivitySplashBinding

/**
 * Pantalla de carga con el logo de DMujeres Tracking. Al terminar entra al
 * onboarding o directo a la pantalla principal según si ya se configuró.
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.logoImage.alpha = 0f
        binding.logoImage.animate().alpha(1f).setDuration(700).start()

        Handler(Looper.getMainLooper()).postDelayed({
            val onboardingDone = AppConfig(this).onboardingDone
            val target = if (onboardingDone) MainActivity::class.java
            else OnboardingActivity::class.java
            startActivity(Intent(this, target))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            finish()
        }, 1700)
    }
}