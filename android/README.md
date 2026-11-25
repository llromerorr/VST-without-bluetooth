# WOB 6.1 - App Android (Universal: Android 5.0 a 15+)

Aplicación emisora **WOB 6.1 (Without Bluetooth)** optimizada para máxima fluidez sobre Wi-Fi congestionado sin bloqueos TCP.

## Novedades en la Versión 6.1
- **Lectura Segura de Audio (`AudioRecord.read` acumulativo):** Garantiza cero pérdida de muestras parciales en cualquier dispositivo Samsung, Xiaomi, Motorola o MediaTek.
- **Cola Desacoplada de 2 Segundos (Zero-GC):** Separa captura y red en hilos dedicados sin descartar tramas ni recortar saltos en música viva.
- **Modo Iglesia Ultra-Ligero Automático (`62.5 pps` + `FEC K=4`):** Reduce drásticamente paquetes por segundo (~78 pps totales en modo congestionado) manteniendo agregación hardware Wi-Fi sin saturación `AC_VO`.
- **Volumen Seguro al 65% + Notificación Clara:** Muestra `Volumen: XX%` al frente con controles `[-]` / `[+]`.

## Compilación
```cmd
build_apk.bat
```
