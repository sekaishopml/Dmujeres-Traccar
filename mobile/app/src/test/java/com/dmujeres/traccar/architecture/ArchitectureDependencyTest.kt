package com.dmujeres.traccar.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FASE R4/R5: congela las REGLAS de dependencia entre paquetes por dominio.
 *
 * No es un test de comportamiento: es un guardarraíl barato (JVM, sin Android)
 * contra la erosión arquitectónica. Las reglas documentan la dirección
 * permitida del grafo real (`docs/DEPENDENCY_RULES.md`); las excepciones son
 * fronteras aceptadas y explícitas, no cabos sueltos.
 *
 * Si este test falla, la solución NO es borrar la regla: es corregir la
 * dependencia o justificar y documentar la nueva arista.
 */
class ArchitectureDependencyTest {

    private val root = File("src/main/java/com/dmujeres/traccar")

    private data class Rule(
        val pkg: String,
        val forbidden: Set<String>,
        val exceptions: Set<String> = emptySet(),
    )

    /**
     * Aristas prohibidas por paquete (el resto de dependencias por dominio son
     * las que ya existen; ver DEPENDENCY_RULES.md):
     * - core no depende de nadie,
     * - config solo de core,
     * - data solo de config/core,
     * - los dominios de datos (health/location/oem/sensors) no dependen de
     *   tracking ni de ui,
     * - transport no conoce outbox (la cuarentena entra por callback),
     * - outbox no conoce tracking/ui ni otros dominios de presentación,
     * - tracking es orquestador: puede usar todos los dominios, pero de UI solo
     *   la fachada del widget (`ui.widget`),
     * - platform resuelve el PendingIntent del launcher (ui) pero no conoce
     *   tracking ni dominios de datos.
     */
    private val rules = listOf(
        Rule(
            "core",
            forbidden = setOf(
                "config", "data", "diagnostics", "health", "location", "oem", "outbox",
                "platform", "readiness", "recovery", "sensors", "tracking", "transport", "ui",
            ),
        ),
        Rule("config", forbidden = setOf("data", "diagnostics", "health", "location", "oem", "outbox", "platform", "readiness", "recovery", "sensors", "tracking", "transport", "ui")),
        Rule("data", forbidden = setOf("diagnostics", "health", "location", "oem", "outbox", "platform", "readiness", "recovery", "sensors", "tracking", "transport", "ui")),
        Rule("health", forbidden = setOf("tracking", "ui")),
        Rule("location", forbidden = setOf("tracking", "ui")),
        Rule("oem", forbidden = setOf("tracking", "ui", "outbox", "transport", "health", "location", "diagnostics", "recovery", "readiness")),
        Rule("sensors", forbidden = setOf("tracking", "ui", "outbox", "transport", "health", "location", "diagnostics", "recovery", "readiness")),
        Rule("diagnostics", forbidden = setOf("tracking", "ui", "transport")),
        Rule("transport", forbidden = setOf("data", "outbox", "tracking", "ui", "recovery", "readiness", "health", "location", "oem", "sensors")),
        Rule("outbox", forbidden = setOf("tracking", "ui", "diagnostics", "health", "location", "oem", "platform", "readiness", "recovery", "sensors")),
        Rule("platform", forbidden = setOf("tracking", "data", "diagnostics", "health", "location", "oem", "outbox", "readiness", "recovery", "sensors", "transport")),
        Rule("readiness", forbidden = setOf("ui")),
        Rule("recovery", forbidden = setOf("ui")),
        Rule(
            "tracking",
            forbidden = setOf("ui"),
            // Única arista de presentación aceptada: refresco de la fachada del widget.
            exceptions = setOf("ui.widget"),
        ),
    )

    @Test
    fun packageDependenciesRespectDomainRules() {
        assertTrue("No existe $root (¿working dir de test distinto?)", root.isDirectory)

        val violations = mutableListOf<String>()
        for (rule in rules) {
            val dir = File(root, rule.pkg)
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    file.readLines()
                        .mapNotNull { IMPORT_REGEX.find(it)?.groupValues?.get(1) }
                        .filterNot { it == rule.pkg.substringBefore('.') || it.startsWith("${rule.pkg.substringBefore('.')}.") }
                        .filterNot { full -> rule.exceptions.any { full == it || full.startsWith("$it.") } }
                        .map { full -> full.substringBeforeLast('.') }
                        .filter { target -> rule.forbidden.any { target == it || target.startsWith("$it.") } }
                        .forEach { target ->
                            violations += "${file.relativeTo(root)}: ${rule.pkg} no debe importar $target"
                        }
                }
        }

        assertTrue(
            "Violaciones de dependencias entre paquetes:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    private companion object {
        val IMPORT_REGEX = Regex("^import com\\.dmujeres\\.traccar\\.([\\w.]+)")
    }
}
