package com.dmujeres.traccar.oem

import android.content.Context

/**
 * Estados posibles de la capa de protección OEM (Fase 3-4, SOLO informativa:
 * no cambia onboarding ni comportamiento).
 * - OK: sin gate de fabricante conocido para este equipo.
 * - ACTION_REQUIRED: hay guía del fabricante y aún no fue completada.
 * - CONFIGURED_UNVERIFIABLE: guía completada. La app NO puede verificar
 *   programáticamente que el OEM dejó de matar el proceso (las pantallas
 *   relevantes están protegidas con permisos de sistema) → "configurado, no
 *   verificable automáticamente".
 * - UNSUPPORTED: fabricante detectado con gate agresivo pero sin guía mapeada.
 */
enum class OemState { OK, ACTION_REQUIRED, CONFIGURED_UNVERIFIABLE, UNSUPPORTED }

/**
 * Perfil informativo del gate OEM del dispositivo. Espejo de
 * [VendorSettings.Guide] sin tipos Android (los Intent no cruzan a JVM-test).
 */
data class OemProfile(
    val vendorKey: String?,
    val vendorName: String,
    val guideTitle: String,
    val guideSteps: List<String>,
    val primaryIntentPresent: Boolean,
    val secondaryIntentPresent: Boolean,
) {

    /** Estado de protección según [OemProtection.stateFor]. */
    fun stateFor(guideDone: Boolean, batteryExempt: Boolean): OemState =
        OemProtection.stateFor(vendorKey, guideDone, batteryExempt)

    companion object {

        /**
         * Fábrica desde [VendorSettings.Guide] (lado Android: Guide lleva
         * Intent). vendorKey == null → null (sin gate conocido, nada que
         * mostrar). vendorKey != null sin guía → perfil con steps vacíos
         * (clasifica UNSUPPORTED vía [OemProtection.stateFor]).
         */
        fun fromGuide(vendorKey: String?, guide: VendorSettings.Guide?): OemProfile? {
            if (vendorKey == null) return null
            return OemProfile(
                vendorKey = vendorKey,
                vendorName = guide?.vendorName ?: vendorKey,
                guideTitle = guide?.title.orEmpty(),
                guideSteps = guide?.steps ?: emptyList(),
                primaryIntentPresent = guide?.settingsIntent != null,
                secondaryIntentPresent = guide?.secondaryIntent != null,
            )
        }

        /**
         * Wrapper Android: detecta el vendor vigente ([VendorSettings]) y
         * construye el perfil con su guía. null si no hay vendor con gate
         * conocido.
         */
        fun current(context: Context): OemProfile? {
            val vendor = VendorSettings.currentVendor()
            return fromGuide(vendor, VendorSettings.guideFor(vendor))
        }
    }
}

/**
 * Clasificación PURA (JVM) del estado de protección OEM. La detección del
 * vendor y la guía viven en VendorSettings; aquí solo las reglas.
 */
object OemProtection {

    /**
     * Espejo puro de los vendorKey con guía en [VendorSettings.guideFor].
     * Debe mantenerse sincronizado: si VendorSettings agrega un vendor,
     * agregarlo aquí también.
     */
    val KNOWN_VENDORS: Set<String> =
        setOf("xiaomi", "samsung", "honor", "infinix", "tecno", "zte")

    /**
     * Reglas (documentadas):
     * - vendorKey == null → OK: sin gate conocido, nada que configurar.
     * - vendorKey != null sin guía mapeada → UNSUPPORTED (fabricante agresivo
     *   no cubierto: no hay guía que mostrar ni verificación posible).
     * - vendorKey con guía y !guideDone → ACTION_REQUIRED.
     * - vendorKey con guía y guideDone → CONFIGURED_UNVERIFIABLE.
     * - batteryExempt NO cambia el veredicto hoy: la exención de batería es
     *   solo uno de los pasos de la guía (auto-inicio, candado en recientes,
     *   etc.). Se mantiene en la firma para no romperla al evolucionar.
     */
    fun stateFor(vendorKey: String?, guideDone: Boolean, batteryExempt: Boolean): OemState = when {
        vendorKey == null -> OemState.OK
        vendorKey !in KNOWN_VENDORS -> OemState.UNSUPPORTED
        !guideDone -> OemState.ACTION_REQUIRED
        else -> OemState.CONFIGURED_UNVERIFIABLE
    }
}
