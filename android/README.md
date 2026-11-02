# WOB 3.0 - App Android (Universal: Android 5.0 a 15+)

Aplicación emisora para **WOB 3.0 (Without Bluetooth)**. Captura el flujo de audio digital interno de cualquier reproductor (YouTube, Spotify, pistas de ensayo) y lo envía en tiempo real por Wi-Fi UDP a REAPER / DAW.

## Novedades en la Versión 3.0
- **Compatibilidad Universal Total:** Compatible desde Android 5.0 (Lollipop) hasta Android 15+ (`minSdkVersion=21`, `targetSdkVersion=34`).
- **Soporte Completo para Android 14+:** Registro nativo de `MediaProjection.Callback` y permisos de primer plano de proyección de medios.
- **MulticastLock de Hardware:** Habilita el paso de paquetes UDP de broadcast en procesadores MediaTek, Transsion (Tecno/Infinix), Qualcomm y Exynos.
- **Barrido Rápido de Subred:** Descubrimiento automático ultra-rápido en menos de 20 ms sin importar la subred del router (`192.168.0.x`, `192.168.1.x`, `10.0.0.x`, etc.).
- **Controles de Volumen en Notificación:** Botones fijos `[-]` y `[+]` siempre visibles en la notificación compacta (`MediaStyle`) sin necesidad de desplegarla.
- **Fader de Estudio en Pantalla:** Deslizador de 0 a 100% que escala la ganancia PCM digital sin tocar el volumen del parlante físico.
- **Persistencia en Segundo Plano:** `WakeLock`, `WifiLock` de alto rendimiento y exención de optimización de batería.

## Compilación
Requiere **Android SDK Build-Tools** y **JDK**:
```cmd
build_apk.bat
```
Generará `bin\WOB-3.0.apk` y `bin\WOB.apk`.
