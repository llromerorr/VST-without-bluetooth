# WOB 4.0 - VST Plugin (Windows 64-bit)

Plugin receptor VST2 de audio digital sobre UDP de ultra-baja latencia y estabilidad blindada para DAWs (REAPER, Ableton, FL Studio, etc.), conectado a teléfonos Android mediante la app **WOB 4.0**.

## Novedades en la Versión 4.0
- **Búfer Inteligente Auto-Adaptativo:** Ajusta el colchón elástico de forma autónoma entre 60 ms y 220 ms según el *jitter* y las condiciones de la red Wi-Fi. Inmune a cortes al caminar por la casa o en redes 2.4 GHz congestionadas.
- **Handshake Bidireccional & Heartbeat:** Emite respuestas `NA_OFFER` de descubrimiento y acuses `NA_ACK` periódicos hacia el teléfono para certificar la conexión en vivo.
- **Ocultamiento Suave de Jitter (PLC):** Desvanecimiento suave en micro-baches para evitar silencios abruptos o clics digitales.
- **Compensación Proporcional de Clock Drift:** Resampleo continuo inaudible ($\pm 0.4\%$) que fija el búfer permanentemente en el colchón objetivo sin acumulación de latencia ni vaciados.
- **Suma y Mezcla Multidispositivo:** Permite conectar múltiples teléfonos en simultáneo sumando sus canales con limitador suave anti-clipping.
- **Interfaz Nativa Win32:** Muestra el búfer adaptativo en milisegundos, jitter en vivo, IP del teléfono conectado y medidores de pico VU.

## Compilación
Requiere **GCC** (MinGW-w64 o MSYS2):
```cmd
build_vst.bat
```
Generará `bin\WOB-4.0.dll` y `bin\WOB.dll`.

## Instalación
1. Copia `WOB-4.0.dll` (o `WOB.dll`) en tu carpeta de VSTs:
   - `C:\Program Files\VSTPlugins\` o
   - `C:\Program Files\REAPER (x64)\Plugins\FX\`
2. En REAPER:
   - Ve a `Options` -> `Preferences` -> `VST` -> `Re-scan`.
   - Inserta `WOB 4.0` en cualquier pista.
