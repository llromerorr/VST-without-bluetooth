# WOB 6.0 (Without Bluetooth) 🚀
### Audio Wi-Fi Profesional Cero-Pérdida para Iglesias y Eventos en Vivo

[![Release](https://img.shields.io/github/v/release/llromerorr/VST-without-bluetooth?color=blue&label=Versi%C3%B3n%206.0)](https://github.com/llromerorr/VST-without-bluetooth/releases)
[![Platform](https://img.shields.io/badge/Plataforma-Windows%2064bit%20%7C%20Android%205.0%20a%2015%2B-blue.svg)](#)
[![Format](https://img.shields.io/badge/Formato-VST2%2064--bit-orange.svg)](#)
[![Transport](https://img.shields.io/badge/Transporte-TCP%200%25--P%C3%A9rdida%20%2B%20B%C3%BAfer%20600--1500ms-brightgreen.svg)](#)
[![License](https://img.shields.io/badge/Licencia-MIT-purple.svg)](#)

**WOB 6.0** (*Without Bluetooth*) transmite el audio digital interno de Android hacia **REAPER / DAWs** en Windows con **estabilidad blindada para auditorios e iglesias llenas**. Combina transmisión **TCP optimizada (0% pérdida de paquetes)** con un **búfer elástico real de 600 ms a 1,500 ms**, de forma **100% automática y sin modos complejos**.

---

## 📥 Descargas Oficiales (Versión 6.0)

Descarga los binarios precompilados directamente desde [**GitHub Releases**](https://github.com/llromerorr/VST-without-bluetooth/releases):

| Componente | Archivo de Descarga Directa | Descripción |
| :--- | :--- | :--- |
| **Plugin VST2 (Windows 64-bit)** | [👉 **Descargar WOB-6.0.dll**](https://github.com/llromerorr/VST-without-bluetooth/releases/latest/download/WOB-6.0.dll) | Plugin receptor TCP + respaldo UDP con búfer real de 600 a 1,500 ms. |
| **App Móvil (Android Universal)** | [👉 **Descargar WOB-6.0.apk**](https://github.com/llromerorr/VST-without-bluetooth/releases/latest/download/WOB-6.0.apk) | App Android 5.0 a 15+ con TCP inteligente, QoS Wi-Fi y volumen seguro al 65%. |

*(También disponibles con nombres cortos: [`WOB.dll`](https://github.com/llromerorr/VST-without-bluetooth/releases/latest/download/WOB.dll) y [`WOB.apk`](https://github.com/llromerorr/VST-without-bluetooth/releases/latest/download/WOB.apk))*

---

## 🛡️ Por qué WOB 6.0 NO se corta cuando llega toda la congregación

* **🔒 Transporte TCP Inteligente Anti-Pérdida (con respaldo UDP automático):**
  - El descubrimiento de la PC sigue siendo automático por UDP (`58200`), pero la transmisión principal de audio corre sobre **TCP optimizado (`TCP_NODELAY`, `DSCP EF QoS`, anti-bloat `SO_SNDBUF`)**.
  - **0% pérdida real de paquetes** sin inundar el router Wi-Fi con paquetes extra de paridad ni tormentas NACK.
  - Si un firewall estricto de Windows bloqueara TCP, cambia solo al instante a respaldo UDP con FEC + NACK.
* **🧠 Búfer Elástico Real Grande (600 ms hasta 1,500 ms / 1.5 seg):**
  - Arranca con **600 ms reales** llenos en memoria desde el primer segundo y se expande automáticamente hasta **1,500 ms** si el router Wi-Fi de la iglesia sufre congestión fuerte, absorbiendo congelamientos completos de casi medio segundo sin cortes audibles.
  - Incluye recorte suave anti-acumulación para evitar retrasos de varios segundos tras caídas prolongadas, y expansión instantánea en pausas de silencio sin desafinar la música.
* **📡 Compresión Adaptativa Liviana (BFP-8 / BFP-6 + Silencio a 0 bytes):**
  - En redes limpias transmite en **BFP-8 HD** (`~800 kbps`). Ante congestión baja automáticamente a **BFP-6 empaquetado doble (`62.5 pps`, ~600 kbps)** para consumir lo mínimo del aire Wi-Fi.
* **🚫 Aislamiento contra 4G/5G + Watchdog de Reconexión Automática:**
  - Fija el socket directamente a la red Wi-Fi/Ethernet (`Network.getSocketFactory()`) evitando saltos a datos móviles cuando el Wi-Fi está pesado.
  - Si un socket TCP se congela > 3.5 s por interferencia extrema, el *watchdog* lo reinicia automáticamente en milisegundos sin detener la captura de audio.
* **📊 Telemetría Honesta y Clara en REAPER:**
  - Muestra en tiempo real **IP PC**, transporte activo (`TCP 0%-Perdida`), **Búfer Real / Objetivo (`ms`)**, *Jitter* real, **Pérdida Real (0–100%)** y medidores VU estéreo.

---

## 🎬 Guía Rápida de Instalación y Uso

### 1. En la Computadora (REAPER / DAW)
1. Descarga [`WOB-6.0.dll`](https://github.com/llromerorr/VST-without-bluetooth/releases/latest/download/WOB-6.0.dll).
2. Cópialo a `C:\Program Files\VSTPlugins\` *(o `C:\Program Files\REAPER (x64)\Plugins\FX\`)*.
3. En **REAPER**: `Options` -> `Preferences` -> `Plug-ins` -> `VST` -> **Re-scan**, e inserta **`WOB 6.0`** en una pista.

> **Tip de estabilidad física:** Si puedes, conecta la PC de REAPER por **cable Ethernet** al router. En emergencias donde el Wi-Fi del lugar colapse, también funciona directo conectando el celular al PC por cable USB con **Anclaje USB**.

### 2. En el Teléfono Android
1. Descarga e instala [`WOB-6.0.apk`](https://github.com/llromerorr/VST-without-bluetooth/releases/latest/download/WOB-6.0.apk).
2. Abre **WOB**, toca **CONECTAR** y acepta el permiso de captura.
3. Inicia en volumen seguro (**65%**) con lectura directa del volumen en la notificación.

---

## 🛠️ Compilación desde el Código Fuente

```cmd
cd vst && build_vst.bat
cd android && build_apk.bat
```

Licencia: **MIT License**. Libre para uso personal, ministerial y comercial.
