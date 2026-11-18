# WOB 4.0 - App Android (Universal: Android 5.0 a 15+)

Aplicación emisora para **WOB 4.0 (Without Bluetooth)**. Captura el flujo de audio digital interno de cualquier reproductor (YouTube, Spotify, pistas de ensayo) y lo envía en tiempo real por Wi-Fi UDP a REAPER / DAW con handshake real bidireccional y auto-recuperación de enlace.

## Novedades en la Versión 4.0
- **Handshake Real Bidireccional:** Semáforo de estados en vivo (Amarillo = Buscando a REAPER, Verde = Confirmado y transmitiendo en vivo, Naranja = Señal débil/reconectando).
- **Fin a la Falsa Conexión:** La app no finge conexión ni envía audio a ciegas; solo transmite cuando REAPER ha respondido afirmativamente.
- **Heartbeat & Auto-Reconexión:** Monitoriza acuses de recibo periódicos desde el DAW. Si la señal Wi-Fi cae temporalmente por paredes, la app reintenta el enlace sola en segundo plano sin reiniciar la app.
- **Compatibilidad Universal Total:** Compatible desde Android 5.0 (Lollipop) hasta Android 15+ (`minSdkVersion=21`, `targetSdkVersion=34`).
- **Soporte Completo para Android 14+:** Registro nativo de `MediaProjection.Callback` y permisos de primer plano de proyección de medios.
- **MulticastLock de Hardware:** Habilita el paso de paquetes UDP de broadcast en procesadores MediaTek, Transsion (Tecno/Infinix), Qualcomm y Exynos.
- **Controles de Volumen Dual:** Botones fijos `[-]` y `[+]` siempre visibles en la notificación compacta (`MediaStyle`) y fader en pantalla (0% a 100%).
- **Persistencia en Segundo Plano:** `WakeLock`, `WifiLock` de alto rendimiento y exención de optimización de batería.

## Compilación
Requiere **Android SDK Build-Tools** y **JDK**:
```cmd
build_apk.bat
```
Generará `bin\WOB-4.0.apk` y `bin\WOB.apk`.
