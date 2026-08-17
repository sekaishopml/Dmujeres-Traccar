package com.dmujeres.traccar

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.databinding.ActivitySplashBinding

/**
 * Pantalla de carga con el logo de DMujeres Tracking. Antes de entrar pide al
 * colaborador TODOS los permisos (ubicación, notificaciones, batería y GPS),
 * para que la jornada funcione aunque se cierre la pantalla.
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Handler(Looper.getMainLooper()).postDelayed({
            val onboardingDone = AppConfig(this).onboardingDone
            val target = if (onboardingDone) MainActivity::class.java
            else OnboardingActivity::class.java
            startActivity(Intent(this, target))
            finish()
        }, 1600)
    }
}