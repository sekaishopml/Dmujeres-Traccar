# Colaborar en el servidor (dos personas a la vez)

El servidor es la copia que **compila y publica** (tiene SDK, llaves, Firebase y el
panel en vivo). Cada persona trabaja en **su propio clon** dentro del servidor y
los cambios se cruzan por GitHub. Así no se pisan los archivos y todo queda
versionado.

| | Agente (asistente) | Tú (santi) |
|---|---|---|
| Carpeta de trabajo | `/DMujeres-Tracking` | `/home/santi/DMujeres-Traccar` |
| Rama | `main` / `feat/*` | `santi/*` |
| Publicar a macias | sí | sí (ver comando) |

## 1. Entrar al servidor

- **Usuario**: `santi` · **Contraseña temporal**: la que te compartieron
  (cámbiala al entrar con `passwd`).
- IP y puerto: el mismo del panel (`68.168.20.219:22`).
- Prueba rápida desde Windows (PowerShell):
  ```powershell
  ssh santi@68.168.20.219
  ```

## 2. Editar con VS Code (Remote-SSH)

1. Instala **Visual Studio Code** y la extensión **Remote - SSH**.
2. `F1` → *Remote-SSH: Add New SSH Host* → `santi@68.168.20.219` → guarda.
3. `F1` → *Remote-SSH: Connect to Host* → escribe la contraseña.
4. `File → Open Folder` → `/home/santi/DMujeres-Traccar` → abre `fallback/`.
5. Edita, guarda y compila en la terminal integrada (Ctrl+ñ).

## 3. Comandos del día a día

```bash
cd /home/santi/DMujeres-Traccar

# Traer lo último (antes de empezar)
git pull --rebase

# Compilar solo (APK para tu teléfono)
cd fallback && ./gradlew :app:assembleRegularDebug     # interfaz (rápido)
./gradlew :app:assembleGoogleRelease                   # release de flota
cd ..

# Publicar a macias (escribe en el panel en vivo)
DMJ_PUBLISH_ROOT=/DMujeres-Tracking bash infrastructure/scripts/build-pilot.sh

# Subir tus cambios
git checkout -b santi/mi-cambio
git add -A && git commit -m "mi cambio"
git push -u origin santi/mi-cambio
```

Notas:
- El APK de tu build queda en `fallback/app/build/outputs/apk/...`; para
  instalarlo en tu teléfono puedes copiarlo al panel (`cp ... /DMujeres-Tracking/dashboard/build/`)
  y abrirlo desde el navegador del teléfono, o usar adb por WiFi.
- **RAM**: el servidor tiene poca. Al terminar de compilar: `./gradlew --stop`.
- **Publicar de a uno**: si vas a publicar, avisa; la allowlist sigue siendo
  solo `macias` (fail-closed en el script).

## 4. Reglas para no chocar

1. Cada quien en su carpeta (nunca editar en la carpeta del otro).
2. Antes de empezar: `git pull --rebase`; antes de terminar: commit + push.
3. Ramas propias (`santi/*`); el agente integra en `main`.
4. Un solo publicador a la vez (avisar por chat antes de correr `build-pilot.sh`).
5. Si dos cambios tocan el mismo archivo, gana el merge de git: se resuelve en
   el clon del que integra (el agente).
