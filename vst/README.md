# WOB 5.0 - VST Plugin (Windows 64-bit)

Plugin receptor VST2 nivel broadcast sobre UDP (`WOB 5.0`, compatible hacia atrás con `WOB 4.0`) para REAPER y otros DAWs en Windows 64-bit.

## Novedades en la Versión 5.0
- **Búfer por Ranuras Indexadas por Secuencia (4 segundos):** Elimina desfases y pérdida de colchón ante paquetes perdidos o desordenados; soporta expansión elástica autónoma de **220 ms hasta 850 ms**.
- **Recuperación Dual XOR FEC + Selective `NA_NACK`:** Repara pérdidas aisladas mediante paridad XOR al instante y dispara solicitudes `NA_NACK` inmediatas ante ráfagas perdidas en redes Wi-Fi saturadas.
- **Medición `QPC` de Microsegundos:** Sustituye `GetTickCount()` para medir *jitter* real y ajustar el colchón de seguridad con precisión de sub-milisegundo.
- **PLC por Similitud de Onda (*Pitch-Matched*) y Pausas Silenciosas:** Rellena micro-huecos extremos manteniendo continuidad de fase y aprovecha silencios naturales para recuperar colchón sin alterar el tono.
- **Interfaz Win32 con Telemetría Completa:** Muestra IP local de la PC, IP del teléfono, Tier activo (`HD` / `Blindado` / `Supervivencia`), búfer en ms, jitter, pérdida %, paquetes curados FEC/NACK y medidores VU.

## Compilación
Requiere **GCC** (MinGW-w64 o MSYS2):
```cmd
build_vst.bat
```
Generará `bin\WOB-5.0.dll` y `bin\WOB.dll`.

## Instalación
1. Copia `WOB-5.0.dll` (o `WOB.dll`) en tu carpeta de VSTs:
   - `C:\Program Files\VSTPlugins\` o
   - `C:\Program Files\REAPER (x64)\Plugins\FX\`
2. En REAPER:
   - Ve a `Options` -> `Preferences` -> `VST` -> `Re-scan`.
   - Inserta `WOB 5.0` en cualquier pista.
