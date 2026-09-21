package com.dmujeres.traccar.oem

import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Detecta fabricantes con gestores de batería agresivos y ofrece la guía exacta para
 * mantener la app viva en segundo plano (autostart, sin restricciones, app launch...).
 */
object VendorSettings {

    /** Paquete de la app (para extras de las ROMs). */
    const val APP_PACKAGE = "com.dmujeres.traccar"

    data class Guide(
        val vendorName: String,
        val title: String,
        val steps: List<String>,
        val settingsIntent: Intent?,
        /** Botón secundario opcional (página de la app, segundo ajuste del OEM). */
        val secondaryIntent: Intent? = null,
    )

    /**
     * Cadena de intents OEM (fase 1, investigación docs/audit/PERMS_*): se
     * intentan en orden y el primero lanzable gana; el llamador cae a Ajustes
     * de la app. Los componentes son de ROMs del fabricante: siempre try/catch.
     * El marcador {pkg} en extras se sustituye por el paquete de la app.
     */
    data class IntentSpec(
        val label: String,
        val componentPackage: String? = null,
        val componentClass: String? = null,
        /** Acción implícita (fallback cuando el componente no existe en la ROM). */
        val action: String? = null,
        /** Extras del fabricante; {pkg} se reemplaza por el paquete real. */
        val extras: Map<String, String> = emptyMap(),
    )

    /**
     * Cadenas por OEM (fuentes en docs/audit/PERMS_RESEARCH_*.md):
     * HONOR MagicOS usa com.hihonor.systemmanager (el huawei legacy es fallback);
     * Infinix/Tecno usan com.transsion.*; ZTE com.zte.powersavemode;
     * Xiaomi com.miui.securitycenter; Samsung Device Care (lool).
     */
    fun intentChainFor(vendor: String?): List<IntentSpec> = when (vendor) {
        "honor" -> listOf(
            IntentSpec("HONOR MagicOS: Inicio de aplicaciones", "com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            IntentSpec("HONOR: control de aplicaciones", "com.hihonor.systemmanager", "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            IntentSpec("HONOR: apps protegidas", "com.hihonor.systemmanager", "com.hihonor.systemmanager.optimize.process.ProtectActivity"),
            // Entrada pública de batería/consumo (VERIFIED en campo, hushd):
            // el detalle con "permitir actividad en segundo plano" es protegido.
            IntentSpec("HONOR: batería y consumo", "com.hihonor.systemmanager", "com.hihonor.systemmanager.power.ui.HwPowerManagerActivity"),
            IntentSpec("Huawei legacy: Inicio de aplicaciones", "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            IntentSpec("Huawei legacy: control de aplicaciones", "com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            IntentSpec("Huawei legacy: apps protegidas", "com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            IntentSpec("Huawei legacy: batería y consumo", "com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"),
        )
        "infinix", "tecno" -> listOf(
            IntentSpec("Transsion: Gestión de auto-inicio", "com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
            IntentSpec("Transsion: ahorro de batería por app", "com.transsion.batterylab", "com.android.settings.batterysave.BatteryLab\$TranAppSavingActivity"),
            // Paquete CORREGIDO tras investigación (antes apuntaba a phonemaster).
            IntentSpec("Transsion: ahorro global", "com.transsion.batterylab", "com.transsion.powersave.activity.PowerSavaMainActivity"),
            IntentSpec("Transsion: gestor de energía", "com.transsion.phonemaster", "com.cyin.himgr.powermanager.views.activity.PowerManagerActivity"),
            IntentSpec("Transsion: administrador de energía", "com.transsion.phonemanager", "com.itel.autobootmanager.activity.AutoBootMgrActivity"),
            IntentSpec("MediaTek: control de auto-inicio", "com.mediatek.autobootcontroller", "com.mediatek.autobootcontroller.AutoBootAppManageActivity"),
        )
        "zte" -> listOf(
            IntentSpec("ZTE MyOS: Gestión inteligente", "com.zte.powersavemode", "com.zte.powersavemode.appsmartoptimizer.AppSmartOptimizeActivity"),
            IntentSpec("ZTE: detalle de control", "com.zte.powersavemode", "com.zte.powersavemode.appsmartoptimizer.AppSmartOptimizeDetailActivity"),
            IntentSpec("ZTE: apps de alto consumo", "com.android.settings", "com.android.settings.Settings\$HighPowerApplicationsActivity"),
        )
        "xiaomi" -> listOf(
            IntentSpec("MIUI/HyperOS: Auto-inicio", "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            // Fallback por acción (VERIFIED MIUI 10–14): por si la activity
            // clásica cambió de nombre en HyperOS.
            IntentSpec("MIUI/HyperOS: Auto-inicio (acción)", action = "miui.intent.action.OP_AUTO_START"),
            IntentSpec(
                "MIUI/HyperOS: editor de permisos",
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity",
                action = "miui.intent.action.APP_PERM_EDITOR",
                extras = mapOf("extra_pkgname" to "{pkg}"),
            ),
            IntentSpec(
                "MIUI/HyperOS: editor alterno",
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.AppPermissionsEditorActivity",
                action = "miui.intent.action.APP_PERM_EDITOR",
                extras = mapOf("extra_pkgname" to "{pkg}"),
            ),
            IntentSpec(
                "MIUI/HyperOS: editor (por paquete)",
                "com.miui.securitycenter",
                action = "miui.intent.action.APP_PERM_EDITOR",
                extras = mapOf("extra_pkgname" to "{pkg}"),
            ),
            IntentSpec(
                "MIUI/HyperOS: batería por app",
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                extras = mapOf("package_name" to "{pkg}", "packageName" to "{pkg}"),
            ),
            IntentSpec("MIUI/HyperOS: apps ocultas de batería", "com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity"),
            IntentSpec("MIUI/HyperOS: lista de batería (acción)", action = "miui.intent.action.POWER_HIDE_MODE_APP_LIST"),
        )
        "samsung" -> listOf(
            IntentSpec(
                "Samsung: apps que nunca se suspenden",
                "com.samsung.android.lool",
                action = "com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY",
                extras = mapOf("activity_type" to "2"),
            ),
            IntentSpec("Samsung: batería (One UI 5+)", "com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            IntentSpec("Samsung: batería (legacy)", "com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            IntentSpec("Samsung China: batería", "com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        else -> emptyList()
    }

    /** Intent concreto de un spec (sin lanzar). */
    fun intentFor(spec: IntentSpec, targetPackage: String): Intent {
        val intent = Intent()
        spec.action?.let { intent.action = it }
        if (spec.componentPackage != null && spec.componentClass != null) {
            intent.component = android.content.ComponentName(spec.componentPackage, spec.componentClass)
        } else if (spec.componentPackage != null) {
            intent.setPackage(spec.componentPackage)
        }
        for ((key, value) in spec.extras) {
            intent.putExtra(key, value.replace("{pkg}", targetPackage))
        }
        return intent
    }

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
            manufacturer.contains("zte") || brand.contains("zte") -> "zte"
            else -> null
        }
    }

    /**
     * En Xiaomi/Redmi/Poco e Infinix/Tecno el diálogo del sistema para ubicación
     * en segundo plano suele fallar en silencio (o solo ofrece denegar) según
     * versión de MIUI/HiOS. En esos equipos se manda directo a Ajustes de la
     * app, donde "Permitir siempre" sí funciona. Resto de marcas: diálogo normal.
     */
    fun requiresSettingsForBackground(vendor: String? = currentVendor()): Boolean =
        vendor == "xiaomi" || vendor == "infinix" || vendor == "tecno"

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
            // Fuente: docs/audit/OS_DEVICE_SAMSUNG_A52.md (§2, §6, §7). El botón
            // abre "Aplicaciones sin autosuspensión" (activity_type=2); además,
            // el auto-revoke (Android 13+) solo se desactiva a mano en la ficha.
            steps = listOf(
                "1. En 'Aplicaciones sin autosuspensión' (la lista que abre el botón), busca DMujeres y desmárcala para que no entre en suspensión.",
                "2. Verifica que DMujeres NO esté en 'Aplicaciones suspendidas' ni en 'Suspensión profunda'.",
                "3. Ajustes → Aplicaciones → DMujeres → Batería → 'Sin restricciones'.",
                "4. En la ficha de la app (Batería/Desinstalar), desactiva 'Eliminar permisos si la app no se usa' (auto-revoke / 'Pausar actividad en la app si no se usa').",
            ),
            // Deeplink oficial documentado por Samsung (Never sleeping apps,
            // activity_type=2). La app verifica que Device Care esté instalado
            // antes de lanzarlo; si falla, cae a Ajustes de la aplicación.
            settingsIntent = samsungNeverSleepingIntent(),
            secondaryIntent = appDetailsIntent(),
        )
        "honor" -> Guide(
            vendorName = "Honor",
            title = "Honor (MagicOS 8/9): permite el inicio automático",
            // Fuente: docs/audit/OS_DEVICE_HONOR_LGNLX3.md (§3, §4, §8).
            // MagicOS 8/9 movió App launch a Ajustes → Aplicaciones; los
            // deeplinks históricos pueden no existir y la app cae a la página
            // de la app.
            steps = listOf(
                "1. Ajustes → Aplicaciones → Gestión del inicio de aplicaciones → DMujeres → 'Gestionar manualmente'.",
                "2. Activa los 3 toggles: Inicio automático, Inicio secundario y Ejecutar en segundo plano.",
                "3. Ajustes → Batería → activa 'Mantener la conexión durante el sueño'.",
                "4. Ajustes → Batería → Optimización de batería → DMujeres → 'No permitir'.",
                "5. En Recientes, baja la tarjeta de DMujeres y toca el candado para fijarla.",
                "Nota: en MagicOS 8/9 los accesos directos pueden no existir; si el botón falla, la app cae a la página de la app (Ajustes de DMujeres) y hay que seguir estos pasos a mano.",
            ),
            // MagicOS: com.hihonor.systemmanager; el huawei legacy queda de
            // fallback (ver intentChainFor). Si el deeplink falla, Onboarding
            // recorre la cadena y termina en Ajustes de la app.
            settingsIntent = intentChainFor("honor").firstOrNull()?.let { intentFor(it, APP_PACKAGE) }
        )
        "infinix", "tecno" -> Guide(
            vendorName = if (vendor == "infinix") "Infinix" else "Tecno",
            title = "Infinix/Tecno: permite la app en segundo plano",
            // Fuente: docs/audit/OS_DEVICE_INFINIX_X6531.md (§2, §6) y
            // PERMS_RESEARCH_INFINIX_HONOR.md (§A): Hiber proxya alarmas y el
            // freezer (AddFreezeApp) es contraproducente.
            steps = listOf(
                "1. Phone Master → Caja de herramientas → Gestión de auto-inicio → activa DMujeres.",
                "2. Phone Master → Battery Lab/Ahorro de energía → DMujeres → 'Sin restricciones'.",
                "3. Hiber/Ahorro de energía de Phone Master: DMujeres sin restricciones ('Power Saving Management for apps' = OFF; Power Boost/Power Marathon = OFF).",
                "4. NO uses 'Congelar aplicaciones' (Freezer/AddFreezeApp) con DMujeres: la hibernaría y cortaría el seguimiento.",
                "5. Sleep Mode / ahorro de datos: desactiva el modo avión nocturno y permite datos en segundo plano para DMujeres.",
                "6. Ajustes → Batería → desactiva el ahorro de energía para DMujeres.",
                "7. En Recientes, baja la tarjeta de DMujeres y toca el candado para fijarla.",
            ),
            // Transsion (VERIFIED): auto-inicio y ahorro por app; la cadena
            // incluye MediaTek autoboot como fallback (docs/audit).
            settingsIntent = intentChainFor("infinix").firstOrNull()?.let { intentFor(it, APP_PACKAGE) }
        )
        "zte" -> Guide(
            vendorName = "ZTE",
            title = "ZTE: saca la app del 'control de IA'",
            // "Gestión inteligente" se abre con la cadena de intents (VERIFIED
            // en campo y OSS); si la ROM la bloquea, Onboarding cae al detalle
            // del control y termina en la página de la app.
            // Fuente: docs/audit/OS_DEVICE_ZTE_Z2450.md (§2, §7): "Gestión
            // inteligente" es la whitelist que respeta el congelador del vendor.
            steps = listOf(
                "1. Ajustes → Batería → Gestión inteligente → DMujeres → 'Sin control' (es la whitelist que respeta el congelador ZTE).",
                "2. En la página de la app: desactiva 'Pausar actividad en la app si se deja de usar'.",
                "3. Mantén 'Optimización de batería' → DMujeres → 'No optimizar'.",
                "4. Si la ROM muestra el candado: en Recientes fija DMujeres con el candado.",
            ),
            // Deep link confirmado en campo (ZTE MyOS): pantalla "Gestión
            // inteligente"/AppSmartOptimize. Si la ROM no la expone, el
            // llamador cae a Ajustes genéricos (mismo patrón que Samsung).
            settingsIntent = autostartIntent(
                "com.zte.powersavemode",
                "com.zte.powersavemode.appsmartoptimizer.AppSmartOptimizeActivity",
            ) ?: Intent(Settings.ACTION_SETTINGS),
            secondaryIntent = appDetailsIntent(),
        )
        else -> null
    }

    private fun autostartIntent(componentPkg: String, componentClass: String): Intent? =
        runCatching {
            val component = android.content.ComponentName(componentPkg, componentClass)
            Intent().setComponent(component)
        }.getOrNull()

    /**
     * Deeplink oficial de Samsung Device Care para "Never sleeping apps"
     * (apps que Samsung NUNCA suspende). Documentado en
     * developer.samsung.com/mobile/app-management.html. Solo se usa si el
     * paquete com.samsung.android.lool está instalado; OnboardingActivity
     * además envuelve el lanzamiento con try/catch y fallback a Ajustes.
     */
    fun samsungNeverSleepingIntent(): Intent? =
        Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY").apply {
            setPackage("com.samsung.android.lool")
            putExtra("activity_type", 2)
        }

    /** true si Samsung Device Care (lool) está instalado en este equipo. */
    fun samsungDeviceCareInstalled(packageManager: android.content.pm.PackageManager?): Boolean {
        if (packageManager == null) return false
        return runCatching {
            packageManager.getPackageInfo("com.samsung.android.lool", 0)
            true
        }.getOrDefault(false)
    }

    private fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:$APP_PACKAGE")
        }
}
