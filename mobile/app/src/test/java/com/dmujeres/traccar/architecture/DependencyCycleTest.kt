package com.dmujeres.traccar.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R7: detección de CICLOS de dependencia entre paquetes por dominio.
 *
 * No impone "cero ciclos" (el grafo real aún tiene un ciclo de orquestación
 * documentado en `docs/TECHNICAL_DEBT.md`), pero SÍ rompe el build cuando
 * aparece un ciclo NUEVO: ningún paquete fuera de la deuda aceptada puede
 * participar en uno.
 *
 * Deuda aceptada (ciclo de orquestación): tracking ↔ readiness ↔ recovery ↔
 * ui ↔ platform ↔ diagnostics. Romperlo requiere interfaces/eventos (R13/R16/R17)
 * y está planificado; mientras tanto, se congela para que no crezca.
 */
class DependencyCycleTest {

    private val root = File("src/main/java/com/dmujeres/traccar")

    /** Paquetes que HOY pueden participar del ciclo de orquestación (deuda). */
    private val acceptedCyclePackages = setOf(
        "tracking", "readiness", "recovery", "ui", "platform", "diagnostics",
    )

    @Test
    fun noPackageOutsideAcceptedDebtParticipatesInACycle() {
        assertTrue("No existe $root (¿working dir de test distinto?)", root.isDirectory)

        val graph = buildGraph()
        val cyclic = graph.keys.filter { pkg -> reachesItself(pkg, graph) }.toSet()
        val offenders = cyclic - acceptedCyclePackages

        assertTrue(
            "Ciclos NUEVOS detectados (paquetes fuera de la deuda aceptada): $offenders\n" +
                "Paquetes en ciclo hoy: ${cyclic.sorted()}\n" +
                "Regla: romper el ciclo con interfaces/eventos, no ampliar la deuda.",
            offenders.isEmpty(),
        )
    }

    @Test
    fun transportNeverParticipatesInACycle() {
        val graph = buildGraph()
        assertTrue(
            "transport no debe participar en ningún ciclo (R11: Outbox -> Transport, nunca al revés)",
            !reachesItself("transport", graph),
        )
    }

    private fun buildGraph(): Map<String, Set<String>> {
        val graph = mutableMapOf<String, MutableSet<String>>()
        root.listFiles { f -> f.isDirectory }?.forEach { dir ->
            val pkg = dir.name
            val targets = graph.getOrPut(pkg) { mutableSetOf() }
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    file.readLines().forEach { line ->
                        val m = IMPORT_REGEX.find(line) ?: return@forEach
                        val target = m.groupValues[1].substringBefore('.')
                        if (target != pkg) targets += target
                    }
                }
        }
        return graph
    }

    /** ¿`start` puede volver a sí mismo siguiendo importaciones? (detección de ciclo). */
    private fun reachesItself(start: String, graph: Map<String, Set<String>>): Boolean {
        val visited = mutableSetOf<String>()
        val stack = ArrayDeque(graph[start].orEmpty())
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current == start) return true
            if (!visited.add(current)) continue
            stack.addAll(graph[current].orEmpty())
        }
        return false
    }

    private companion object {
        val IMPORT_REGEX = Regex("^import com\\.dmujeres\\.traccar\\.([\\w.]+)")
    }
}
