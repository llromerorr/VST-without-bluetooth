# WOB 6.0 - App Android (Universal: Android 5.0 a 15+)

Aplicación emisora para **WOB 6.0 (Without Bluetooth)** diseñada para transmisión en vivo sin cortes en auditorios e iglesias congestionadas.

## Novedades en la Versión 6.0
- **Transporte TCP Inteligente (0% pérdida):** Descubre REAPER por UDP (`58200`) y abre un flujo **TCP optimizado** (`TCP_NODELAY`, `DSCP EF QoS`, `SO_SNDBUF=64KB`) para entregar el 100% del audio sin generar tráfico extra de paridad ni tormentas NACK. Incluye respaldo UDP automático.
- **Watchdog Anti-Congelamiento + Auto-Reconexión:** Si el Wi-Fi bloquea un envío > 3.5 s, reinicia el enlace limpiamente manteniendo la captura de audio activa sin volver a pedir permisos.
- **Compresión Adaptativa BFP-8 / BFP-6 + Silencio:** Reduce el envío a `~600 kbps` (`62.5 pps`) en redes saturadas y `~10 kbps` durante silencios digitales.
- **Aislamiento de Red Wi-Fi / Ethernet:** Fija los sockets directamente sobre la interfaz local (`Network.getSocketFactory()` / `bindSocket`) evitando saltos silenciosos a 4G/5G.
- **Volumen Seguro (65%) y Notificación Limpia:** Muestra `Volumen: XX%` visible de inmediato con controles `[-]` / `[+]`.

## Compilación
```cmd
build_apk.bat
```
Generará `bin\WOB-6.0.apk` y `bin\WOB.apk`.
