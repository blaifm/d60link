# D60 Link

🇬🇧 [English version](README.md)

App Android (Material 3) para ver y descargar las fotos de una Nikon D60 (o
cualquier cámara PTP) por cable USB OTG, sin pasar por el ordenador. Interfaz en
inglés y español (según el idioma del móvil).

## Qué hace

- Se abre sola al conectar la cámara.
- Galería con las miniaturas que trae la propia cámara, agrupada por días.
- Marca las fotos ya descargadas y filtra "Todas / Nuevas".
- Selección múltiple (pulsación larga) o por día.
- "Bajar nuevas" en un toque. Guarda en `DCIM/NikonD60` vía MediaStore.
- Vista ampliada con zoom antes de descargar (los NEF se ven con su miniatura).
- Material 3 con colores dinámicos.

## Uso

1. En la cámara: **Menú configuración › USB › MTP/PTP**.
2. Cable OTG **Mini-USB (Mini-B) macho → USB-C macho**.
3. Enciende la cámara, conéctala y acepta el permiso USB.

## Compilar

Requisitos: JDK 17+, Android SDK con `platforms;android-35` y `build-tools;35.0.0`.

```bash
echo "sdk.dir=/ruta/al/android-sdk" > local.properties
gradle assembleRelease
```

La APK queda en `app/build/outputs/apk/release/`. El build de release se firma con la clave debug.

## Estructura

- `Camera.kt`: acceso PTP con `android.mtp.MtpDevice` (un único hilo), miniaturas, vista previa y descarga a MediaStore.
- `MainViewModel.kt`: estado, selección, registro de fotos descargadas.
- `MainActivity.kt`: detección USB, permisos, conexión y desconexión.
- `Ui.kt` / `Theme.kt`: interfaz en Jetpack Compose.
- `res/values` (inglés) y `res/values-es` (español): textos de la interfaz.

## Pendiente de verificar con la cámara real

- Detección en modo MTP/PTP y aviso en modo almacenamiento masivo.
- Fechas de las fotos.
- Que MediaStore acepte los NEF (`image/x-nikon-nef`).
