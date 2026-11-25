# WOB 6.1 - VST Plugin (Windows 64-bit)

Plugin receptor VST2 UDP Pro para REAPER y DAWs en Windows 64-bit (`WOB 6.1`).

## Novedades en la Versión 6.1
- **Búfer Elástico Real (450 ms a 1,200 ms) con Fast-Resume (160 ms):** Absorbe ráfagas pesadas desde el arranque y nunca bloquea el audio en silencios largos si se recupera tras una caída extrema.
- **Telemetría Real Corregida:** Separa *Jitter* en ms de la **Pérdida Real (0.0–100.0%)**, mostrando además **Búfer Real / Objetivo (`ms`)** y **Curados FEC/NACK**.
- **Soporte Multi-Frecuencia (44.1 kHz / 48 kHz / 96 kHz)** y PLC por similitud de onda (*pitch-matched*).

## Compilación
```cmd
build_vst.bat
```
