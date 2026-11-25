# WOB 6.0 - VST Plugin (Windows 64-bit)

Plugin receptor VST2 nivel broadcast (servidor dual **TCP + UDP**, compatible hacia atrás con WOB 4/5) para REAPER y DAWs en Windows 64-bit.

## Novedades en la Versión 6.0
- **Receptor Dual TCP + UDP (Puerto `58200`):** Recibe transmisiones TCP de cero pérdida con `TCP_NODELAY` o respaldo UDP con FEC + NACK.
- **Búfer Real Grande (600 ms a 1,500 ms) sobre Anillo de 8.19 segundos:** Arranca con 600 ms reales garantizados y amplía hasta 1.5 segundos en condiciones severas, usando expansión silenciosa sin desafinar la música ni acumular retraso infinito.
- **Telemetría Real Corregida:** Muestra **Búfer Real / Objetivo (`ms`)**, **Pérdida Real (0–100%)** sin falsos >100% por *jitter*, **Jitter (ms)**, **Cortes**, IP local de la PC y medidores VU.

## Compilación
```cmd
build_vst.bat
```
Generará `bin\WOB-6.0.dll` y `bin\WOB.dll`.
