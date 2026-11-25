# WOB 5.0 - App Android (Universal: Android 5.0 a 15+)

Aplicación emisora de nivel broadcast para **WOB 5.0 (Without Bluetooth)**. Captura el audio digital interno del sistema y lo transmite hacia REAPER / DAW con arquitectura multi-hilo desacoplada, protección contra saturación Wi-Fi y auto-adaptación transparente.

## Novedades en la Versión 5.0
- **Arquitectura Desacoplada sin Bloqueos (Zero-GC):** Hilo dedicado de captura separado del hilo de red UDP mediante anillo en memoria (`512 ms`) para impedir pérdidas de muestras cuando el chip Wi-Fi sufre contención.
- **Compresión Auto-Adaptativa BFP-8 / BFP-6 + XOR FEC:** Reduce a más de la mitad la tasa de paquetes por segundo (`62.5 pps` en modo congestionado) y añade paquetes de paridad XOR para curar pérdidas en 0 ms.
- **Caché de Retransmisión Selectiva (`NA_NACK`):** Conserva ~2 segundos de tramas codificadas en memoria y responde al instante a solicitudes `NA_NACK` desde REAPER.
- **Aislamiento de Red Wi-Fi + Prioridad WMM QoS:** Vincula explícitamente el socket UDP al transporte Wi-Fi/Ethernet (`Network.bindSocket`) evitando saltos a datos móviles 4G/5G y marca tráfico con prioridad `DSCP EF` (`0xB8`).
- **Descubrimiento Limpio Multi-Subred:** Soporte automático de subredes `/22`, `/23`, `/24` y USB Tethering sin tormentas ARP sobre redes ocupadas.
- **Volumen Seguro y Notificación Clara:** Inicia siempre en `65%` al conectar y muestra `Volumen: XX%` de forma directa en la notificación con botones compactos `[-]`/`[+]`.

## Compilación
Requiere **Android SDK Build-Tools** y **JDK**:
```cmd
build_apk.bat
```
Generará `bin\WOB-5.0.apk` y `bin\WOB.apk`.
