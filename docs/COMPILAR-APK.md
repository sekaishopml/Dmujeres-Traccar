# Compilar el APK — guía para personalizar textos

Guía en lenguaje simple para cambiar los textos de bienvenida y login de la
app **DMujeres Tracking** y generar un APK nuevo para probar o publicar.

> Idea general: los textos están en un solo archivo. Los cambias, corres un
> script que arma el APK, lo pasas al celular y lo instalas encima. No necesitas
> saber programar, solo seguir los pasos en orden.

---

## 1. Qué es `mobile/build-apk.sh`

Es un script (una receta automática) que arma el APK instalable a partir del
código actual, con tus textos personalizados incluidos.

Se ejecuta siempre desde la carpeta `mobile/`:

```bash
cd mobile
```

Tiene 4 usos:

| N.º | Comando | Para qué sirve |
|---|---|---|
| 1 | `./build-apk.sh debug` | APK de prueba. Rápido, para probar en tu celular. |
| 2 | `./build-apk.sh release` | APK final. El que se publica en GitHub para todas. |
| 3 | `./build-apk.sh debug 1.0.63` | APK de prueba + cambia la versión a `1.0.63`. |
| 4 | `./build-apk.sh release 1.0.63` | APK final + cambia la versión a `1.0.63`. |

Reglas simples:

- Si no pones nada, hace `debug` (opción 1).
- Cambia el ejemplo `1.0.63` por la versión real que quieras (por ejemplo
  `1.0.64`, `1.1.0`). Pregunta cuál toca si no estás segura.
- El modo `release` es el único que sirve para publicar. El `debug` es solo
  para probar tú.

---

## 2. Paso a paso: cambiar textos de bienvenida y login

### 2.1. Abre el archivo exacto

Solo toca este archivo:

```text
mobile/app/src/main/res/values/strings.xml
```

Ábrelo con cualquier editor de texto. Verás líneas así:

```xml
<string name="welcome_title">¡Hola, bienvenido!</string>
```

- Lo de `name="..."` es el **nombre de la clave** (no lo cambies).
- Lo que está entre `>` y `</string>` es el **texto que ve la usuaria**
  (eso sí lo cambias).

### 2.2. Tabla de claves principales

Estas son las que normalmente quieres personalizar:

| Clave | Dónde se ve | Texto actual |
|---|---|---|
| `welcome_title` | Bienvenida, título | ¡Hola, bienvenido! |
| `welcome_body` | Bienvenida, explicación | Vamos a dejar tu app lista en 4 pasos cortos… |
| `step_location` | Paso 1, título | 1. Tu ubicación |
| `step_location_detail` | Paso 1, detalle | Tu recorrido se guarda aunque cierres la app. |
| `grant_location` | Paso 1, botón | Permitir siempre |
| `step_notifications` | Paso 2, título | 2. Tus avisos |
| `step_notifications_detail` | Paso 2, detalle | Te avisamos si algo necesita tu atención. |
| `grant_notifications` | Paso 2, botón | Permitir avisos |
| `step_battery` | Paso 3, título | 3. Que no se apague |
| `step_battery_detail` | Paso 3, detalle | Así Android no pausa tu ubicación en reposo. |
| `grant_battery` | Paso 3, botón | Sin límites |
| `step_gps` | Paso 4, título | 4. Tu lugar en el mapa |
| `step_gps_detail` | Paso 4, detalle | Con el GPS vemos tu lugar en el mapa. |
| `grant_gps` | Paso 4, botón | Activar ubicación |
| `login_title` | Login, título | ¡Bienvenido! |
| `login_subtitle` | Login, subtítulo | Ingresa tu usuario y contraseña para iniciar tu jornada. |
| `login_body` | Login, ayuda | Ingresa los datos que te entregó CCTV y pulsa "Iniciar jornada"… |
| `finish` | Último botón de bienvenida | Empezar |

Las claves que terminan en `_detail` son la frase pequeña debajo de cada paso.
Si cambias un título, revisa que su `_detail` siga teniendo sentido.

### 2.3. Reglas para no romper la app

1. **No borres ni cambies lo de `name="..."`.** Solo cambia el texto de
   adentro. Si borras una línea entera, la app no compila.
2. **Conserva los códigos como `%1$s` y `%1$d`.** Son huecos donde la app pone
   un dato (versión, batería, minutos). Ejemplo: en
   `Versión %1$s · toca para actualizar`, deja el `%1$s` tal cual, aunque
   muevas las palabras de lugar.
3. **Evita `&`, `<` y `>` sueltos en el texto.** Rompen el archivo. Si los
   necesitas, avisa y los escribimos de forma segura.
4. **Acentos, ñ y ¿? ¡ están permitidos.** Escríbelos con normalidad.
5. **No toques nada fuera de `strings.xml`.** Ni el script
   `mobile/build-apk.sh` ni otro código.

Ejemplo de cambio bien hecho:

```xml
<!-- Antes -->
<string name="welcome_title">¡Hola, bienvenido!</string>
<!-- Después -->
<string name="welcome_title">¡Hola, bienvenida a DMujeres!</string>
```

### 2.4. Compila el APK

1. Guarda el archivo `strings.xml`.
2. Abre una terminal en la carpeta `mobile/` y corre:

   ```bash
   ./build-apk.sh debug
   ```

   Usa `release` solo cuando el texto ya te guste y quieras la versión final.
3. Espera a que diga `OK ✅ APK listo`. La primera vez tarda varios minutos
   (ver sección 3).

### 2.5. Dónde queda el APK

- Prueba (debug):

  ```text
  mobile/app/build/outputs/apk/debug/app-debug.apk
  ```

- Final (release):

  ```text
  mobile/app/build/outputs/apk/release/app-release.apk
  ```

El script al final te muestra la ruta y la versión incluida.

### 2.6. Cómo instalarlo encima

1. Pasa el archivo APK al celular (por cable USB, WhatsApp, Drive o descarga).
2. En el celular, toca el archivo APK para instalarlo.
3. Si pregunta, permite "instalar apps desconocidas" solo para esa vez.
4. Se instala **encima**: no borra tus datos ni tu sesión.
5. Abre la app y revisa tus textos nuevos.

### 2.7. Cómo deshacer (revertir) tus cambios de texto

Si algo no te gusta, vuelve al texto anterior sin tocar nada más:

```bash
git checkout -- mobile/app/src/main/res/values/strings.xml
```

Luego vuelve a compilar con `./build-apk.sh debug` para generar el APK con
los textos originales.

> Si además cambiaste la versión con el script (opción 3 o 4) y quieres
> deshacer eso también:
>
> ```bash
> git checkout -- mobile/app/build.gradle.kts
> ```

---

## 3. Si algo falla (troubleshooting)

### 3.1. Root vs opencode: la caché es por usuario

- La primera compilación descarga herramientas de Android (Gradle, plugin,
  dependencias). Son como **~1 GB** y tarda varios minutos. Es normal.
- Esa descarga se guarda en la **caché de cada usuario** (carpeta `~/.gradle`).
  Lo que descarga `root` no lo ve `opencode`, y al revés.
- Conclusión: **la primera compilación de cada usuario siempre tarda**,
  aunque el otro usuario ya haya compilado antes. Las siguientes son rápidas.
- Consejo: compila siempre con el mismo usuario para no descargar dos veces.

> **Firma única (importante):** Android solo deja instalar encima si la firma
> coincide. Los keystores debug de `root` y `opencode` ya están sincronizados
> en este servidor (misma huella de la 1.0.60 en adelante), así que un APK
> compilado por cualquiera de los dos instala encima sin desinstalar. Si algún
> día ves "firma no coincide / App not installed", avísame antes de borrar.
>
> **Equipo:** el keystore debug vive versionado en `mobile/keystore/`
> y `app/build.gradle.kts` ya lo usa: cualquier PC compila con la MISMA
> firma automáticamente. Nunca subas ahí tu keystore de producción.

### 3.2. Sin internet

- **Con internet:** el script descarga lo que falte y compila.
- **Sin internet:** solo funciona si **ese mismo usuario ya compiló antes**
  (porque usa su caché). Si es su primera vez sin internet, falla.
- Si estás sin internet, no cambies de usuario: usa el que ya compiló.

### 3.3. Error "plugin no encontrado" (o similar de Gradle)

Típico mensaje: `Plugin was not found`, `Could not resolve`, `No cached version`.

Significa una de estas dos cosas:

1. No hay internet y ese usuario aún no tiene la caché completa, o
2. Se compiló antes con otro usuario (su caché no te sirve).

Solución:

1. Conéctate a internet.
2. Compila con el mismo usuario una vez: `./build-apk.sh debug`.
3. A partir de ahí ya puedes compilar offline con ese usuario.

### 3.4. Falta de disco o memoria

- Libera espacio si ves errores como `No space left on device` o
  `OutOfMemory`. La compilación Android necesita varios GB libres.
- Cierra otros programas pesados y vuelve a intentarlo.
- Si el error persiste, pide ayuda con el mensaje completo del error.

### 3.5. Error de Java (JAVA_HOME / JDK 17)

- Se necesita **JDK 17 o superior**.
- Si ves `no hay Java instalado` o errores de `JAVA_HOME`:
  1. Comprueba con `java -version` que exista y sea 17+.
  2. Si hay varias versiones de Java, pide ayuda para fijar `JAVA_HOME` a la
     del JDK 17 antes de compilar.

---

## 4. Cómo publicar la versión final

Tienes dos caminos:

**Camino fácil (recomendado):** pídeme que la lance por ti con una frase como:

```text
lanza la 1.0.64
```

Yo compilo el release, lo subo a GitHub y dejo lista la actualización
automática.

**Camino manual:** si lo quieres hacer tú:

1. Compila el release con la versión nueva:

   ```bash
   cd mobile
   ./build-apk.sh release 1.0.64
   ```

   (Cambia `1.0.64` por la versión real.)
2. Toma el archivo `mobile/app/build/outputs/apk/release/app-release.apk`.
3. Súbelo como release en GitHub con ese número de versión.
4. Avísame para verificar que la actualización automática lo detecta.

> Nunca publiques el APK `debug` como versión oficial: solo el `release`.

---

## 5. Nota: qué hace cada chequeo del script

Cuando corres `mobile/build-apk.sh`, hace esto en orden:

1. **Localiza el Android SDK.** Busca la variable `ANDROID_HOME` o las rutas
   típicas (`/opt/android-sdk`, etc.). Sin SDK no puede armar el APK y se
   detiene con un error claro.
2. **Comprueba Java.** Verifica que exista `java` (se necesita JDK 17+). Te
   muestra la versión que encontró.
3. **Cambia la versión (solo si le pasaste número).** Si usaste la opción 3 o
   4, sube el `versionCode` en 1 y pone el `versionName` nuevo en
   `mobile/app/build.gradle.kts`. Si no pasaste versión, no toca nada.
4. **Pasa los tests y compila.** Corre primero las pruebas unitarias
   (`testDebugUnitTest`) y luego arma el APK (`assembleDebug` o
   `assembleRelease`). Detecta si hay internet: con internet permite
   descargar dependencias; sin internet compila en modo offline (solo funciona
   con caché previa de ese usuario).
5. **Muestra el resultado.** Te dice la ruta del APK, su tamaño y la versión
   incluida, y te recuerda cómo pasarlo al celular.

---

*Archivo de textos: `mobile/app/src/main/res/values/strings.xml`. Script:
`mobile/build-apk.sh` (no lo edites, solo ejecútalo). APK de prueba:
`mobile/app/build/outputs/apk/debug/app-debug.apk`. APK final:
`mobile/app/build/outputs/apk/release/app-release.apk`.*
