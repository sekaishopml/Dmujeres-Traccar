# Guía de desarrollo — cliente de respaldo (fallback/) para el piloto

Todo lo de esta guía aplica a la app basada en el cliente oficial que vive en
`fallback/`. El resto del monorepo (app nativa, servidor, panel) no se toca.


## 0. Empezar de cero en tu PC (Windows / Android Studio)

1. **Traer el código** (una vez):
   ```bash
   git clone https://github.com/sekaishopml/Dmujeres-Traccar.git
   cd Dmujeres-Traccar
   git submodule update --init --recursive
   ```
   Si ya tienes el repo clonado de antes: `git pull` (trae la carpeta `fallback/`).
2. **Abrir en Android Studio**: `File → Open` y selecciona la carpeta **`fallback/`**
   (no la raíz del monorepo). Espera el Gradle Sync.
3. **Archivos locales que no vienen en git** (cópialos si los tienes):
   - `fallback/local.properties` → lo crea Android Studio solo (ruta del SDK).
   - `fallback/app/google-services.json` → cópialo de tu proyecto viejo
     (`mobile/app/google-services.json`). Solo hace falta para el flavor
     `google`; para trabajar la interfaz basta el flavor `regular`.
   - Firma: `mobile/keystore.properties` y `mobile/keystore/debug.keystore`
     (solo para compilar `Release`; el día a día usa `Debug`, que firma solo).
4. **Ejecutar en el emulador**: Device Manager → Create Device (Pixel 6, API 34)
   → elige la variante `regularDebug` → Run.
5. **Credenciales de git** (para subir tus cambios): crea un token **nuevo** en
   GitHub (Settings → Developer settings → Tokens) y úsalo como contraseña la
   primera vez que hagas `git push` (Windows lo guarda en el Administrador de
   credenciales). El token que compartiste en el chat queda comprometido:
   rótalo.

### Flujo diario (resumen)

```bash
git checkout -b mi-cambio          # rama para tu cambio
# editar fallback/app/src/main/res/layout/activity_locked_home.xml (interfaz)
#        fallback/app/src/main/res/values/strings.xml         (textos)
#        fallback/app/src/main/java/org/traccar/client/*.kt   (lógica)
bash infrastructure/scripts/build-pilot.sh --no-publish   # APK para tu teléfono
adb install -r fallback/app/build/outputs/apk/google/release/app-google-release.apk
bash infrastructure/scripts/build-pilot.sh                # publicar a macias
git add -A && git commit -m "mi cambio" && git push -u origin mi-cambio
```

> En Windows los scripts `.sh` se ejecutan con **Git Bash** (viene con Git for
> Windows) o con WSL. Si prefieres, también puedes compilar solo desde Android
> Studio (botón Run) y pedir la publicación.

## 1. Build + publicación automática (solo macias)

```bash
bash infrastructure/scripts/build-pilot.sh
```

Qué hace, en orden:
1. **Suma 1 al `versionCode`** en `fallback/app/build.gradle` (215 → 216 → 217…)
   y sube el último número del `versionName` igual (2.1.5 → 2.1.6).
2. Compila los tests del flavor `regular` y el APK release del flavor `google`
   firmado con la clave de flota.
3. **Fija la allowlist del piloto a `["macias"]`** en `rollout.json` (fail-closed:
   es imposible que una versión nueva llegue al resto de la flota por descuido).
4. Publica en la OTA (`latest.json` + APK) y verifica que el servidor lo sirve.

Opciones útiles:

```bash
bash infrastructure/scripts/build-pilot.sh --no-publish   # solo compila (para tu teléfono/emulador)
bash infrastructure/scripts/build-pilot.sh --notes "Actualizar a la versión 2.1.7"
```

Cuando estés conforme con los cambios: `git add -A && git commit`.

## 2. Mover espacios y posiciones verticales (código)

Archivo: `fallback/app/src/main/res/layout/activity_locked_home.xml`
(el home). Cada bloque es un elemento del `LinearLayout` raíz, de arriba a abajo:
logo → estado (pill) → cuadros (Pendientes/Batería/Duración) → botón de jornada
→ botón ACTUALIZAR → versión (footer).

| Lo que quieres mover | Dónde se toca | Efecto |
|---|---|---|
| Bajar/subir el logo | `ImageView` del logo → `android:layout_marginTop` (hoy `28dp`) | separación con el borde superior |
| Bajar/subir TODO el dash | pill de estado → `android:layout_marginTop` (hoy `16dp`) | separación logo ↔ dash |
| Separar estado de los cuadros | contenedor del contenido → `android:layout_marginTop` (hoy `4dp`) | hueco pill ↔ cuadros |
| Hueco entre cuadros y botón | `journey_button` → `android:layout_marginTop` (hoy `8dp`) | |
| Hueco entre botones | `update_button` → `android:layout_marginTop` (hoy `8dp`) | |
| Alto de un bloque | `android:layout_height` de cada tarjeta/botón (cuadros `84dp`, botones `52dp`, pill `52dp`) | |
| Ancho del logo | `ImageView` → `android:layout_width` (hoy `180dp`, con `adjustViewBounds="true"`) | |
| Márgenes internos de la pantalla | raíz → `android:paddingTop/paddingBottom/paddingStart/paddingEnd` | |
| Centrado horizontal | `android:layout_gravity="center_horizontal"` (logo y footer) | |
| Empujar el footer al fondo | el contenedor del dash usa `android:layout_height="0dp"` + `android:layout_weight="1"` | ocupa el espacio flexible y deja el footer abajo |

Regla práctica: **para mover algo verticalmente, cambia su `layout_marginTop`**;
para mover todo el conjunto, cambia el `marginTop` de la pill (primer bloque del
dash). Guarda y en Android Studio pulsa *Sync*; el preview del XML (pestaña
*Design*) refleja el cambio al instante.

## 3. Ver los cambios en el simulador (Android Studio)

1. Abre **Android Studio → Open** y selecciona la carpeta `fallback/`
   (NO la raíz del monorepo).
2. Espera el *Gradle Sync* (la primera vez descarga dependencias).
3. **Device Manager** (icono del teléfono, derecha) → *Create Device* →
   elige **Pixel 6** → *Next* → descarga una imagen **API 34 (Android 14)** →
   *Finish*.
4. En la barra superior elige el AVD y el módulo **app** con el flavor
   `googleRelease` → pulsa **Run** (triángulo verde).
5. Atajos útiles:
   - **Preview de layout sin emulador**: abre `activity_locked_home.xml` y usa
     la pestaña *Design* (arriba a la derecha) — ideal para mover espacios.
   - **Live Edit** (opcional): *Settings → Editor → Live Edit* para ver cambios
     de UI en el emulador sin reinstalar.
   - La app del emulador usa la URL de fábrica del servidor
     (`http://68.168.20.219:5055`); para probar contra otro servidor, entra al
     modo avanzado (5 toques en la versión) y edita el campo *Servidor*.

## 4. Probar en tu propio teléfono

### Por USB (recomendado)
1. En el teléfono: **Ajustes → Acerca del teléfono → Número de compilación**
   (toca 7 veces) para activar *Opciones de desarrollador*.
2. **Ajustes → Sistema → Opciones de desarrollador → Depuración USB: ON**.
3. Conecta el cable y acepta el diálogo *"¿Permitir depuración USB?"*.
4. En tu equipo:
   ```bash
   adb devices                      # debe aparecer tu teléfono como "device"
   bash infrastructure/scripts/build-pilot.sh --no-publish
   adb install -r fallback/app/build/outputs/apk/google/release/app-google-release.apk
   ```
   Al ser la misma firma de flota, se **actualiza encima** sin desinstalar.
5. Ver logs en vivo:
   ```bash
   adb logcat | grep -iE "Dmujeres|AndroidRuntime|CrashReporter"
   ```

### Sin cable (Android 11+)
1. *Opciones de desarrollador → Depuración inalámbrica → Vincular con código*.
2. En tu equipo:
   ```bash
   adb pair <IP>:<PUERTO>           # el código y puerto que muestra el teléfono
   adb connect <IP>:<5555>
   ```

### Instalar directo desde el servidor (sin cable ni PC)
Abre en el navegador del teléfono:
`http://68.168.20.219:999/DMujeres-Tracking-<versión>.apk`

## 5. Flujo diario sugerido

1. Edita el layout (`activity_locked_home.xml`) y mira el *Design*.
2. `bash infrastructure/scripts/build-pilot.sh --no-publish` → instala en tu
   teléfono/emulador y revisa.
3. Cuando te guste: `bash infrastructure/scripts/build-pilot.sh` → macias
   recibe el aviso (banner + botón ACTUALIZAR) con el código nuevo.
4. Revisa el panel (Alertas) si algo falla: los crashes de la app llegan solos a
   `lastDiagnostics.crash` (capturador incluido en la app).
5. `git add -A && git commit` cuando cierres un cambio.

## 6. Todo desde Android Studio (sin terminal)

Lo único que queda fuera de Studio es **publicar la OTA a macias** (necesita las
llaves de flota y escribir en el panel); eso lo hace el agente en un minuto
cuando lo pidas, o tú desde el clon del servidor.

### Configuración una sola vez
1. Abre el proyecto (`fallback/`) y espera el **Gradle Sync**.
2. **Cuenta de GitHub en Studio**: `Settings → Version Control → GitHub → Add
   account → Log In with Token` (pega tu token nuevo). Así el *push* funciona
   desde el IDE sin escribir credenciales.
3. **Tu teléfono**: ya está pareado por WiFi (*Device Manager → Pair Devices
   Using Wi-Fi*). Aparece en la barra superior junto al emulador.

### Día a día (todo en Studio)
| Acción | Cómo |
|---|---|
| Correr tus cambios en el teléfono | selecciona el teléfono arriba → **Run ▶** (instala en segundos) |
| Ver cambios de UI sin reinstalar | **Live Edit** activado (`Settings → Editor → Live Edit`) → guarda (Ctrl+S) |
| Bajar los cambios del agente | **Ctrl+T** (*Update Project*) |
| Guardar y subir tus cambios | **Ctrl+K** (Commit) → **Ctrl+Shift+K** (Push) |
| Crear tu rama | abajo a la derecha: *Git → Branches → New Branch* (`santi/mi-cambio`) |
| Compilar el APK release | panel **Gradle → app → Tasks → build → assembleGoogleRelease** (doble clic) |
| Ver errores del teléfono | pestaña **Logcat** (filtra por `Dmujeres`) |

### Publicar a macias (cuando estés conforme)
Escribe al agente: **"publica la versión"** → se compila en el servidor, sube el
`versionCode`, fija la allowlist a macias y deja el aviso en su teléfono.
