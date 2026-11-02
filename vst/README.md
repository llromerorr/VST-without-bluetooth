# WOB 3.0 - VST Plugin (Windows 64-bit)

Plugin receptor VST2 de audio digital sobre UDP de ultra-baja latencia para DAWs (REAPER, Ableton, FL Studio, etc.), conectado a teléfonos Android mediante la app **WOB 3.0**.

## Novedades en la Versión 3.0
- **Compensación Proporcional de Clock Drift:** Rastreo continuo de la deriva de reloj entre el teléfono y la tarjeta de sonido de la PC. El colchón de audio se mantiene permanentemente estabilizado en ~35 ms sin vaciarse ni desbordarse.
- **Ocultamiento Suave de Jitter:** Ante micro-retrasos de paquetes Wi-Fi causados por el router, el plugin aplica un desvanecimiento suave en lugar de cortar a silencio forzado.
- **Autodescubrimiento Instantáneo:** Responde inmediatamente al barrido de subred UDP en el puerto `58200`.
- **Suma y Mezcla Multidispositivo:** Permite conectar múltiples teléfonos en simultáneo sumando sus canales con limitador suave anti-clipping.
- **Interfaz Nativa Win32:** Medidores de pico VU en tiempo real, contador de teléfonos conectados y estado de enlace.

## Compilación
Requiere **GCC** (MinGW-w64 o MSYS2):
```cmd
build_vst.bat
```
Generará `bin\WOB-3.0.dll` y `bin\WOB.dll`.

## Instalación
1. Copia `WOB-3.0.dll` (o `WOB.dll`) en tu carpeta de VSTs:
   - `C:\Program Files\VSTPlugins\` o
   - `C:\Program Files\REAPER (x64)\Plugins\FX\`
2. En REAPER:
   - Ve a `Options` -> `Preferences` -> `VST` -> `Re-scan`.
   - Inserta `WOB 3.0` en cualquier pista.
