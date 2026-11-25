# WOB 5.0 (Without Bluetooth) 🚀
### Transmisión de Audio Wi-Fi Nivel Broadcast en Vivo (Cero Cortes para Eventos e Iglesias)

[![Release](https://img.shields.io/github/v/release/llromerorr/VST-without-bluetooth?color=blue&label=Versi%C3%B3n%205.0)](https://github.com/llromerorr/VST-without-bluetooth/releases)
[![Platform](https://img.shields.io/badge/Plataforma-Windows%2064bit%20%7C%20Android%205.0%20a%2015%2B-blue.svg)](#)
[![Format](https://img.shields.io/badge/Formato-VST2%2064--bit-orange.svg)](#)
[![Protection](https://img.shields.io/badge/Protecci%C3%B3n-XOR%20FEC%20%2B%20NACK%20%2B%20B%C3%BAfer%20850ms-brightgreen.svg)](#)
[![License](https://img.shields.io/badge/Licencia-MIT-purple.svg)](#)

**WOB 5.0** (*Without Bluetooth*) es una solución profesional de código abierto y cero configuración diseñada para transmitir audio interno de Android en **ambientes en vivo críticos (iglesias llenas, auditorios y eventos)** hacia **REAPER / DAWs** en Windows, resistiendo redes Wi-Fi saturadas por cientos de dispositivos sin tartamudeos, silencios ni caídas.

---

## 📥 Descargas Oficiales (Versión 5.0)

Descarga los binarios precompilados de la **Versión 5.0** directamente desde [**GitHub Releases**](https://github.com/llromerorr/VST-without-bluetooth/releases):

| Componente | Archivo de Descarga Directa | Descripción |
| :--- | :--- | :--- |
| **Plugin VST2 (Windows 64-bit)** | [👉 **Descargar WOB-5.0.dll**](https://github.com/llromerorr/VST-without-bluetooth/releases/latest/download/WOB-5.0.dll) | Plugin receptor de 64 bits con decodificador BFP, XOR FEC, NACK y PLC. |
| **App Móvil (Android Universal)** | [👉 **Descargar WOB-5.0.apk**](https://github.com/llromerorr/VST-without-bluetooth/releases/latest/download/WOB-5.0.apk) | App para Android 5.0 hasta Android 15+ con QoS Wi-Fi y auto-protección. |

*(También disponibles con nombres cortos: [`WOB.dll`](https://github.com/llromerorr/VST-without-bluetooth/releases/latest/download/WOB.dll) y [`WOB.apk`](https://github.com/llromerorr/VST-without-bluetooth/releases/latest/download/WOB.apk))*

---

## 🛡️ Arquitectura Anti-Cortes en WOB 5.0

* **📡 Compresión Adaptativa de Alta Fidelidad (BFP-8 / BFP-6 Noise-Shaped):**
  - En redes limpias transmite a **85+ dB SNR audible (BFP-8)** con la mitad de paquetes por segundo que versiones anteriores.
  - Cuando cientos de celulares saturan el router Wi-Fi, **cambia automáticamente en caliente a BFP-6 empaquetado doble (`62.5 pps`, ~600 kbps)** reduciendo drásticamente el tiempo aire ocupado en el Wi-Fi sin pérdida perceptible en PA.
  - Compresión de silencio digital a `0 bytes` en pausas entre canciones o sermones.
* **🧬 Corrección de Errores XOR FEC + Retransmisión Selectiva (NACK):**
  - **FEC (0 ms de espera):** Paquetes de paridad XOR reparan pérdidas aleatorias al instante sin pedir retransmisión.
  - **Selective `NA_NACK` (cierre de baches largos):** Si una interferencia fuerte borra múltiples paquetes consecutivos (hasta 180+ ms), REAPER solicita exactamente las tramas faltantes y Android las reenvía desde su historial de 2 segundos antes de que el cabezal de reproducción llegue al hueco.
* **🧠 Búfer Elástico Blindado (220 ms a 850 ms) con Alta Precisión `QPC`:**
  - Medición de *jitter* por microsegundos (`QueryPerformanceCounter`).
  - Aprovecha micro-pausas silenciosas para ampliar el colchón instantáneamente sin alterar el tono ni detener la música.
  - Si un corte extremo vacía la cola, aplica PLC por similitud de ciclo de onda (*pitch-matched*) y espera a recargar el colchón completo en silencio suave en lugar de tartamudear.
* **🚀 Prioridad Hardware Wi-Fi (WMM DSCP QoS) & Aislamiento LAN:**
  - Fija el socket directamente al adaptador Wi-Fi/Ethernet (`Network.bindSocket`) evitando que Android cambie tráfico a datos móviles 4G/5G cuando el Wi-Fi está ocupado.
  - Marca paquetes UDP con prioridad **DSCP EF (`0xB8` / WMM Voice)** para adelantarse al tráfico normal de redes sociales de otros asistentes.
  - Descubrimiento sin inundación compatible con subredes grandes `/22`, `/23` y `/24`, además de tethering USB.
* **🔒 Seguridad y Control Visual:**
  - Volumen inicial seguro (**65%**) al conectar para evitar golpes de sonido en consola.
  - Notificación simplificada con lectura directa (`Volumen: XX%`, estado claro `🟢 En vivo`) y botones `[-]`/`[+]`.
  - La ventana VST en REAPER muestra **IP local de la PC**, tier activo, búfer en ms, jitter, pérdida y paquetes recuperados por FEC/NACK.

---

## 🎬 Guía Rápida de Instalación y Uso

### 1. En la Computadora (REAPER / DAW)
1. Descarga [`WOB-5.0.dll`](https://github.com/llromerorr/VST-without-bluetooth/releases/latest/download/WOB-5.0.dll).
2. Cópialo a tu carpeta de VSTs habitual:
   ```text
   C:\Program Files\VSTPlugins\
   ```
   *(o en la carpeta FX de REAPER: `C:\Program Files\REAPER (x64)\Plugins\FX\`)*
3. En **REAPER**:
   * Abre `Options` -> `Preferences` -> `Plug-ins` -> `VST` y presiona **Re-scan**.
   * Agrega un track y carga **`WOB 5.0`**.

> **Importante (Firewall de Windows):** Asegúrate de permitir el acceso en redes privadas para REAPER o el puerto UDP `58200`.

### 2. En el Teléfono Android
1. Descarga e instala [`WOB-5.0.apk`](https://github.com/llromerorr/VST-without-bluetooth/releases/latest/download/WOB-5.0.apk).
2. Abre **WOB**, toca **CONECTAR** y acepta el permiso de captura.
3. El volumen iniciará de forma segura al **65%**; ajústalo desde la pantalla o la notificación.

---

### Especificaciones de Audio (WOB 5.0)
| Parámetro | Especificación |
| :--- | :--- |
| **Protocolo** | UDP Bidireccional (`W5`, Puerto `58200`) con XOR FEC, Selective `NA_NACK` y Telemetría `NA_ACK` |
| **Codificación** | BFP-8 (`~800 kbps`) y BFP-6 Dual-Pack (`~600 kbps`, `62.5 pps`) auto-adaptativos |
| **Frecuencia de Captura / Salida** | 48,000 Hz Estéreo / Adaptable al DAW (44.1 / 48 / 96 kHz) |
| **Búfer Auto-Adaptativo** | **220 ms iniciales, auto-expansivo hasta 850 ms** |
| **QoS / Red** | DSCP EF (`0xB8`), `Network.bindSocket`, soporte `/22`-`/24` y USB Tethering |

---

## 🛠️ Compilación desde el Código Fuente

```text
VST-without-bluetooth/
├── vst/
│   ├── src/WOB.c           # Código fuente C del plugin VST2
│   └── build_vst.bat       # Script de compilación GCC (MinGW / MSYS2)
└── android/
    ├── src/main/           # Código fuente Java y recursos Android
    └── build_apk.bat       # Script de empaquetado y firmado del APK
```

### Compilar el Plugin VST (`vst/`)
```cmd
cd vst
build_vst.bat
```
Generará `vst/bin/WOB-5.0.dll` y lo copiará a tus carpetas VST.

### Compilar la App Android (`android/`)
```cmd
cd android
build_apk.bat
```
Generará `android/bin/WOB-5.0.apk` firmado y zipalineado.

---

## 🤝 Créditos y Licencia

Desarrollado con ❤️ para iglesias, músicos, ingenieros de sonido y productores que necesitan audio confiable sobre Wi-Fi en eventos en vivo reales.

Licencia: **MIT License**. Libre para uso personal y comercial.
