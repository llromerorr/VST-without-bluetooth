# WOB 3.0 (Without Bluetooth) 🚀
### Transmisión de Audio Wi-Fi de Ultra-Baja Latencia de Android a REAPER / DAWs

[![Release](https://img.shields.io/github/v/release/llromerorr/VST-without-bluetooth?color=blue&label=Versi%C3%B3n%203.0)](https://github.com/llromerorr/VST-without-bluetooth/releases)
[![Platform](https://img.shields.io/badge/Plataforma-Windows%2064bit%20%7C%20Android%205.0%20a%2015%2B-blue.svg)](#)
[![Format](https://img.shields.io/badge/Formato-VST2%2064--bit-orange.svg)](#)
[![Latency](https://img.shields.io/badge/Latencia-~35ms%20Ultra--Baja-brightgreen.svg)](#)
[![License](https://img.shields.io/badge/Licencia-MIT-purple.svg)](#)

**WOB 3.0** (*Without Bluetooth*) es una solución profesional, de código abierto y cero configuración para transmitir todo el audio digital interno de tu teléfono Android (YouTube, Spotify, pistas de ensayo de baile, grabaciones) directamente a tu DAW (**REAPER**, Ableton, FL Studio, Cubase, etc.) a través de tu red Wi-Fi local, manteniendo el parlante del teléfono en silencio absoluto y con una latencia ultra baja casi imperceptible (~35 ms), sin desfase y sin cortes.

---

## 📥 Descargas Oficiales (Versión 3.0)

Descarga los binarios precompilados de la **Versión 3.0** directamente desde [**GitHub Releases**](https://github.com/llromerorr/VST-without-bluetooth/releases):

| Componente | Archivo de Descarga Directa | Descripción |
| :--- | :--- | :--- |
| **Plugin VST2 (Windows 64-bit)** | [👉 **Descargar WOB-3.0.dll**](https://github.com/llromerorr/VST-without-bluetooth/releases/latest/download/WOB-3.0.dll) | Plugin de 64 bits para REAPER u otro DAW en Windows. |
| **App Móvil (Android Universal)** | [👉 **Descargar WOB-3.0.apk**](https://github.com/llromerorr/VST-without-bluetooth/releases/latest/download/WOB-3.0.apk) | App para Android 5.0 hasta Android 15+ con autodescubrimiento. |

*(También disponibles con nombres cortos: [`WOB.dll`](https://github.com/llromerorr/VST-without-bluetooth/releases/latest/download/WOB.dll) y [`WOB.apk`](https://github.com/llromerorr/VST-without-bluetooth/releases/latest/download/WOB.apk))*

---

## 🌟 Novedades y Mejoras en WOB 3.0

* **🛡️ Estabilización Anti-Drift (Cero Cortes ni Silencios):**  
  En versiones anteriores, la diferencia microscópica de velocidad entre el reloj del teléfono (48,000 Hz) y la tarjeta de la PC (44,100 Hz) terminaba vaciando el buffer con el paso de los minutos. WOB 3.0 introduce un **resampleador proporcional continuo** que micro-ajusta la velocidad en tiempo real ($\pm 0.5\%$, inaudible) fijando el buffer permanentemente en un colchón óptimo de **~35 ms**.
* **📱 Compatibilidad Universal Total (Android 5.0 a Android 15+):**  
  Cumple con los requisitos estrictos de **Android 14 y 15** (`targetSdkVersion 34`), incluyendo registro de callbacks de `MediaProjection` y permisos de servicios en primer plano.
* **⚡ MulticastLock de Hardware:**  
  Permite la transmisión sin bloqueos en teléfonos con procesadores **MediaTek, Transsion (Tecno/Infinix), Qualcomm y Exynos**, los cuales por ahorro de energía filtran paquetes broadcast si no se solicita el permiso adecuado.
* **🔍 Autodescubrimiento Ultrarrápido de Subred (< 20 ms):**  
  Detecta dinámicamente la subred de tu red Wi-Fi (`192.168.0.x`, `192.168.1.x`, `10.0.0.x`, etc.) y realiza un barrido UDP directo que conecta con REAPER de forma instantánea.
* **🎚️ Control de Volumen Dual:**  
  * **Botones en Notificación:** Controles `[-]` y `[+]` siempre visibles en la vista compacta (`MediaStyle`) sin tener que desplegar la notificación ni salir de YouTube o Spotify.
  * **Fader de Estudio:** Deslizador en pantalla (0% a 100%) que modula la ganancia digital PCM sin afectar el hardware del teléfono.
* **👥 Suma Multidispositivo:**  
  Varios teléfonos pueden conectarse a la vez a la misma pista del DAW para ensayos grupales o colaboraciones con limitador suave (*soft saturation*) integrado.

---

## 🎬 Guía Rápida de Instalación y Uso

### 1. En la Computadora (REAPER / DAW)
1. Descarga [`WOB-3.0.dll`](https://github.com/llromerorr/VST-without-bluetooth/releases/latest/download/WOB-3.0.dll).
2. Cópialo a tu carpeta de VSTs habitual:
   ```text
   C:\Program Files\VSTPlugins\
   ```
   *(o en la carpeta FX de REAPER: `C:\Program Files\REAPER (x64)\Plugins\FX\`)*
3. En **REAPER**:
   * Abre `Options` -> `Preferences` -> `Plug-ins` -> `VST`.
   * Presiona **Re-scan**.
   * Agrega un nuevo track y en la ventana de FX busca **`WOB 3.0`**.
   * La interfaz Win32 se abrirá mostrando el estado y el medidor VU en vivo.

> **Importante (Firewall de Windows):** Asegúrate de permitir el acceso en redes privadas para REAPER o el puerto UDP `58200`.

---

### 2. En el Teléfono Android
1. Descarga e instala [`WOB-3.0.apk`](https://github.com/llromerorr/VST-without-bluetooth/releases/latest/download/WOB-3.0.apk).
2. Abre la app **WOB**.
3. Toca el botón central **CONECTAR**.
4. Acepta el permiso de captura de audio del sistema (*"Iniciar ahora"*).
5. Abre YouTube, Spotify o cualquier reproductor: el sonido sonará inmediatamente por los monitores de tu PC con calidad digital pura a 48 kHz.
6. Ajusta el volumen desde la app o usando los botones `[-]` y `[+]` fijados en la notificación.

---

## 📐 Arquitectura Técnica

```mermaid
flowchart LR
    subgraph Android["📱 Teléfono Android (5.0 a 15+)"]
        App["App de Música\n(YouTube / Spotify)"] -->|"Digital PCM 48kHz"| APC["AudioPlaybackCapture\n+ MediaProjection"]
        APC --> Fader["Fader Digital\n(0 - 100%)"]
        Fader --> UDP_Send["UDP Socket\nSubnet Discovery"]
    end

    UDP_Send -. "Wi-Fi LAN (UDP ~35ms)" .-> UDP_Recv

    subgraph PC["💻 Windows PC (DAW / REAPER)"]
        UDP_Recv["UDP Port 58200\nMulti-Client Receiver"] --> Ring["Ring Buffer\n(65,536 Samples)"]
        Ring --> Resampler["Linear Resampler\n+ Clock Drift Tracking"]
        Resampler --> Mixer["Multi-Client Summer\n+ Soft Limiter"]
        Mixer --> AudioOut["ASIO / Monitores de Estudio"]
    end
```

### Especificaciones de Audio
| Parámetro | Especificación |
| :--- | :--- |
| **Protocolo** | UDP sin sobrecarga de handshake (Puerto `58200`) |
| **Formato de Transmisión** | PCM 16-bit estéreo Little-Endian |
| **Frecuencia de Captura** | 48,000 Hz (Nativo de hardware Android) |
| **Frecuencia de Salida** | Auto-adaptable al host (44,100 Hz, 48,000 Hz, 96,000 Hz) |
| **Colchón Elástico (Jitter Cushion)** | ~35 ms (estabilizado con micro-steering proporcional) |
| **Autodescubrimiento** | Broadcast global + barrido dinámico de subred local |

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
Generará `vst/bin/WOB-3.0.dll`.

### Compilar la App Android (`android/`)
```cmd
cd android
build_apk.bat
```
Generará `android/bin/WOB-3.0.apk`.

---

## 🤝 Créditos y Licencia

Desarrollado con ❤️ para músicos, bailarines, productores e ingenieros de sonido que necesitan conectar el audio del teléfono al DAW sin lidiar con los molestos retrasos, desincronizaciones y cortes del Bluetooth.

Licencia: **MIT License**. Libre para uso personal y comercial.
