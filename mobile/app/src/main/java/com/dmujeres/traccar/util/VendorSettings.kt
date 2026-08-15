package com.dmujeres.traccar.util

import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Detecta fabricantes con gestores de batería agresivos y ofrece la guía exacta para
 * mantener la app viva en segundo plano (autostart, sin restricciones, app launch...).
 */
object VendorSettings {

    data class Guide(
        val vendorName: String,
        val title: String,
        val steps: List<String>,
        val settingsIntent: Intent?
    )

    fun currentVendor(): String? {
        val manufacturer = Build.MANUFACTURER?.lowercase().orEmpty()
        val brand = Build.BRAND?.lowercase().orEmpty()
        return when {
            manufacturer.contains("xiaomi") || brand.contains("xiaomi")
                || brand.contains("redmi") || brand.contains("poco") -> "xiaomi"
            manufacturer.contains("samsung") -> "samsung"
            manufacturer.contains("huawei") || brand.contains("huawei")
                || brand.contains("honor") -> "honor"
            manufacturer.contains("infinix") || brand.contains("infinix") -> "infinix"
            manufacturer.contains("tecno") || brand.contains("tecno") -> "tecno"
            else -> null
        }
    }

    fun guideFor(vendor: String?): Guide? = when (vendor) {
        "xiaomi" -> Guide(
            vendorName = "Xiaomi / Redmi",
            title = "Xiaomi/Redmi: permite que la app funcione siempre",
            steps = listOf(
                "1. Activa 'Auto-inicio' (Seguridad → Permisos → Auto-inicio → tu app).",
                "2. Batería → ahorro de batería de la app → 'Sin restricciones'.",
                "3. Abre las apps recientes y bloquea la app con el candado (bajando su tarjeta).",
            ),
            settingsIntent = autostartIntent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        )
        "samsung" -> Guide(
            vendorName = "Samsung",
            title = "Samsung: evita que suspenda la app",
            steps = listOf(
                "1. Ajustes → Aplicaciones → tu app → Batería → 'Sin restricciones'.",
                "2. Batería → Límites de uso en segundo plano → quita la app de 'Suspensión' y 'Suspensión profunda'.",
                "3. Desactiva 'Poner apps no usadas en suspensión'.",
            ),
            settingsIntent = appDetailsIntent()
        )
        "honor" -> Guide(
            vendorName = "Honor",
            title = "Honor: permite el inicio automático",
            steps = listOf(
                "1. Ajustes → Batería → Inicio de aplicaciones → tu app → 'Gestionar manualmente'.",
                "2. Activa los 3: Auto-inicio, Inicio secundario y Ejecutar en segundo plano.",
                "3. Quita la app de la optimización de batería.",
            ),
            settingsIntent = autostartIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        )
        "infinix", "tecno" -> Guide(
            vendorName = if (vendor == "infinix") "Infinix" else "Tecno",
            title = "Infinix/Tecno: permite la app en segundo plano",
            steps = listOf(
                "1. Phone Master → Caja de herramientas → Gestión de auto-inicio → permite tu app.",
                "2. Ajustes → Batería → desactiva 'Ahorro de energía para apps'.",
                "3. Desactiva 'bloqueos con pantalla apagada' y bloquea la app en Recientes (candado).",
            ),
            settingsIntent = appDetailsIntent()
        )
        else -> null
    }

    private fun autostartIntent(componentPkg: String, componentClass: String): Intent? {
        val intent = Intent().setComponent(android.content.ComponentName(componentPkg, componentClass))
        return runCatching { intent }.getOrNull()?.let { intent }
    }

    private fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:" + "com.dmujeres.traccar")
        }
}
