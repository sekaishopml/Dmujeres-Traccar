# Presentación para la jefa y el equipo — Guía lista para usar

> No necesitas saber programar ni arquitectura: esta guía te da **qué decir,
> qué mostrar y qué responder**. Las diapositivas son 8; el guion está escrito
> para leerse casi tal cual.

---

## 1) La idea en una frase (memorízala)

**"Es un sistema para saber, con pruebas, si cada colaborador cumplió su ruta
de trabajo: la app del teléfono anota dónde está durante la jornada, el servidor
lo guarda y el panel lo muestra en un mapa con reportes."**

---

## 2) Diapositivas (8)

### Diapositiva 1 — Portada
- **DMujeres Tracking** — Control de jornada y ruta.
- Piloto funcionando hoy: 2 equipos (macias y qa-f0). Versión 2.1.32.
- *(Pon una captura del panel con el mapa.)*

### Diapositiva 2 — El problema
- Hoy la ruta se reconstruye "a ojo": llamadas, mensajes, excusas.
- No hay evidencia objetiva para validar rutas, tiempos y cumplimiento.
- Cuando hay multas o reclamos, no hay cómo demostrar qué pasó.

### Diapositiva 3 — La solución (3 piezas, con analogía)
1. **La app (teléfono)** = *el mensajero*: anota la posición durante la jornada
   y la envía por internet. Si no hay señal, **guarda las notas en una libreta**
   y las manda cuando vuelve la conexión.
2. **El servidor (Traccar)** = *la central*: recibe, guarda y ordena todo
   (es la misma base del sistema Traccar que ya conocen, con nuestros ajustes).
3. **El panel (web)** = *la pantalla del supervisor*: mapa en vivo, replay de la
   ruta, jornadas, alertas y reportes.

### Diapositiva 4 — Lo que ya funciona (demo en vivo)
- Jornada manual: **INICIAR / FINALIZAR JORNADA** (nada automático).
- Estado en vivo del equipo: **En línea / Detenido / Sin conexión /
  Deshabilitado** (también con el GPS apagado).
- **Sin internet no se pierde nada**: guarda hasta **5.000 puntos (~3 jornadas)**
  y los envía solos al reconectar.
- **Actualizaciones controladas**: solo llegan a los equipos autorizados
  (hoy: macias y qa-f0).

### Diapositiva 5 — Lo que garantiza la evidencia
- Cada punto tiene hora, precisión y velocidad; el panel marca qué es
  **medido** y qué no.
- Se descartan los "saltos" falsos del GPS (posiciones imposibles).
- Si el teléfono apaga el GPS, la app **lo avisa en pantalla**.
- Todo queda auditado: actualizaciones, recuperaciones y alertas.

### Diapositiva 6 — Seguridad y respaldo
- Acceso al panel por usuario y contraseña, con rol de administrador.
- Respaldos automáticos diarios de la base de datos + verificación semanal.
- El sistema avisa solo (alertas en el panel) si un equipo deja de reportar.
- **Pendiente con aprobación**: rotación de llaves de seguridad y firma
  definitiva de la app (hoy usa una firma de desarrollo).

### Diapositiva 7 — Cómo seguimos
1. **Hoy**: piloto en 2 equipos (macias + qa-f0) y validación en calle.
2. **Semana 1**: medir cobertura de ruta ≥90% y corregir lo que falte.
3. **Semana 2**: sumar 2-3 equipos más (piloto ampliado).
4. **Cierre**: liberar a toda la empresa con la app firmada y llaves rotadas.

### Diapositiva 8 — Lo que necesito de ustedes
- Aprobación para ampliar el piloto (2-3 equipos).
- Aprobación para rotar llaves de seguridad (una vez, sin costo).
- Definir quién supervisa el panel (usuarios y permisos).
- Confirmar el procedimiento de comunicación a los colaboradores.

---

## 3) Guion de la demo en vivo (7 minutos, en este orden)

1. **El teléfono (2 min)**: abre la app.
   - Muestra: logo, estado (**DETENIDO** azul), los 3 cuadros
     (Pendientes / Batería / Duración), botón INICIAR JORNADA.
   - Toca **INICIAR JORNADA** → el estado pasa a **EN LÍNEA** (verde) al moverlo.
   - Menciona: *"si apago el GPS, la app me avisa con un banner"*.
2. **El panel (4 min)**: abre `http://68.168.20.219:999` en el computador.
   - **Mapa**: el equipo aparece; la burbuja cambia de color según el estado.
   - **Repetición de Ruta**: el recorrido con la línea del trazo.
   - **Reportes → Repetición**: muestra el resumen de puntos y paradas.
   - *(Si preguntan por salud/alertas/jornadas: se ocultaron del menú a
     propósito para dejar solo lo que usamos hoy.)*
3. **El correo/OTA (1 min)**: en el teléfono toca el **banner rojo** de
   "Hay una versión disponible" → descarga → instalar. Muestra que las
   actualizaciones llegan solas y solo a los equipos autorizados.

**Plan B de la demo**: si el teléfono no logra GPS en el salón, muestra el
**replay del historial** (ya tiene datos) y el panel. Nunca improvises datos.

---

## 4) Preguntas probables y cómo responder

**"¿Cuánto cuesta?"**
> "El software es de base abierta (Traccar, sin licencias). El gasto es el
> servidor y el internet de los teléfonos, que ya tenemos. No hay costo por
> usuario ni mensualidad."

**"¿Es seguro? ¿Quién ve los datos?"**
> "El panel es privado: entra solo quien tenga usuario y rol. Los respaldos son
> diarios y automáticos. Queda pendiente —con su aprobación— rotar las llaves
> de seguridad y firmar la app con la llave definitiva."

**"¿Y si el trabajador apaga el GPS o el teléfono?"**
> "La app lo detecta y lo muestra: si el GPS está apagado avisa en la pantalla
> del teléfono, y en el panel el equipo pasa a 'Sin conexión' con una alerta.
> No se puede ocultar: el estado se ve en la lista y en el mapa."

**"¿Se puede falsificar la ubicación?"**
> "La app marca cuando la ubicación es simulada y el sistema descarta los
> saltos imposibles. Ningún sistema es 100% infalsificable, pero aquí hay
> evidencia cruzada (hora, precisión, velocidad, ruta) y avisos automáticos."

**"¿Y si no hay internet en la ruta?"**
> "Nada se pierde: la app guarda hasta 5.000 puntos (unas 3 jornadas) y los
> envía sola cuando vuelve la señal. Lo probamos desconectando el servidor."

**"¿Por qué no usan la app oficial de Traccar?"**
> "De hecho el servidor ES Traccar y la app está construida sobre su cliente
> oficial (el más probado en campo). Le agregamos lo de DMujeres: identidad,
> jornadas, avisos de GPS apagado y actualizaciones controladas."

**"¿Qué pasa si algo falla?"**
> "Hay plan de emergencia: se puede instalar el cliente oficial en un teléfono
> y sigue reportando al mismo sistema; y las versiones se pueden revertir
> publicando una corrección. El sistema avisa por alertas antes de que nadie
> tenga que preguntar."

**"¿Cumple con la ley / privacidad?"**
> "Se registra ubicación **solo durante la jornada laboral**, con consentimiento
> y para fines de control de cumplimiento. Recomiendo que lo revise el área
> legal; técnicamente los datos son de la empresa y el acceso es restringido."

**"¿Quién lo mantiene? ¿Y si tú no estás?"**
> "Todo está documentado y versionado en GitHub (guía de trabajo, respaldos y
> procedimientos). Cualquier técnico puede continuar; además el servidor se
> monitorea solo (watchdog) y avisa por el panel."

**"¿No es vigilancia permanente a los empleados?"**
> "No: la jornada la inicia y la cierra el trabajador. Fuera de jornada no se
> registra nada. El objetivo es validar rutas y proteger a quien cumple."

**"¿Cuándo para todos?"**
> "Primero el piloto actual (2 equipos) midiendo cobertura de ruta; luego 2-3
> equipos más; y al cerrar la firma y las llaves, la liberación a toda la
> empresa. Yo propongo ese orden y necesito su aprobación para cada paso."

**"¿Usaste inteligencia artificial para programar?"** (si preguntan)
> "Sí, me apoyé en un asistente de IA para construir y documentar; el resultado
> está probado con pruebas automáticas y validado en teléfono real. Por eso el
> código está en GitHub con toda la trazabilidad."

---

## 5) Frases que NO debes decir

- ❌ "Es 100% infalsificable / inmune a los teléfonos."
- ❌ "Nunca se cae" (mejor: "si falla, avisa y hay plan B").
- ❌ "Yo programé todo solo" (mejor: "lo construí apoyado en un asistente de IA
  y lo validé en campo").
- ❌ Prometer fechas exactas sin la aprobación de llaves y del piloto.
- ❌ Mostrar datos de trabajadores reales sin necesidad (usa qa-f0 para la demo).

---

## 6) Chuleta de 6 datos (para memorizar)

1. **2 equipos** en piloto: **macias** y **qa-f0**.
2. Versión actual: **2.1.32**.
3. Sin internet guarda **5.000 puntos ≈ 3 jornadas**.
4. Reporta **cada 15 s en movimiento** y **cada 60 s detenido**.
5. **4 estados visibles**: En línea / Detenido / Sin conexión / Deshabilitado.
6. Respaldos **diarios** de la base de datos + verificación semanal.

---

## 7) Antes de presentar (checklist de 10 minutos)

- [ ] Teléfono cargado (>50%), GPS encendido y app **2.1.32**.
- [ ] Abrir la app y dejar el **panel** en el navegador (`http://68.168.20.219:999`).
- [ ] Probar el replay con datos del día (deja la pestaña lista).
- [ ] Tener el teléfono con la jornada iniciada y mostrar el estado.
- [ ] Si hay wifi del salón, perfecto; si no, el panel funciona igual.
- [ ] Ten esta guía impresa o en el celular para las preguntas.
