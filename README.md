# WOB 6.1 (Without Bluetooth) 🚀
### Audio Wi-Fi Profesional UDP Pro para Iglesias y Eventos en Vivo (Sin Cortes)

[![Release](https://img.shields.io/github/v/release/llromerorr/VST-without-bluetooth?color=blue&label=Versi%C3%B3n%206.1)](https://github.com/llromerorr/VST-without-bluetooth/releases)
[![Platform](https://img.shields.io/badge/Plataforma-Windows%2064bit%20%7C%20Android%205.0%20a%2015%2B-blue.svg)](#)
[![Format](https://img.shields.io/badge/Formato-VST2%2064--bit-orange.svg)](#)
[![Protection](https://img.shields.io/badge/Protecci%C3%B3n-UDP%20Pro%20%2B%20FEC%20%2B%20NACK%20%2B%20B%C3%BAfer%201200ms-brightgreen.svg)](#)
[![License](https://img.shields.io/badge/Licencia-MIT-purple.svg)](#)

**WOB 6.1** (*Without Bluetooth*) combina la fluidez en tiempo real del transporte **UDP sin bloqueos Head-of-Line** con protección de nivel broadcast (**XOR FEC + Selective `NA_NACK` + Búfer Elástico de 450 ms a 1,200 ms**) para transmitir audio digital de Android a **REAPER / DAWs** incluso con cientos de dispositivos saturando el Wi-Fi de la iglesia.

---

## 📥 Descargas Oficiales (Versión 6.1)

Descarga los binarios precompilados directamente desde [**GitHub Releases**](https://github.com/llromerorr/VST-without-bluetooth/releases):

| Componente | Archivo de Descarga Directa | Descripción |
| :--- | :--- | :--- |
| **Plugin VST2 (Windows 64-bit)** | [👉 **Descargar WOB-6.1.dll**](https://github.com/llromerorr/VST-without-bluetooth/releases/latest/download/WOB-6.1.dll) | Receptor UDP Pro con ranuras por secuencia, XOR FEC, NACK y búfer de 450–1,200 ms. |
| **App Móvil (Android Universal)** | [👉 **Descargar WOB-6.1.apk**](https://github.com/llromerorr/VST-without-bluetooth/releases/latest/download/WOB-6.1.apk) | App Android 5.0 a 15+ con lectura de audio blindada, compresión adaptativa y volumen inicial al 65%. |

*(También disponibles con nombres cortos: [`WOB.dll`](https://github.com/llromerorr/VST-without-bluetooth/releases/latest/download/WOB.dll) y [`WOB.apk`](https://github.com/llromerorr/VST-without-bluetooth/releases/latest/download/WOB.apk))*

---

## 🛡️ Arquitectura Blindada en WOB 6.1

* **⚡ Transporte UDP Pro sin Bloqueos (A-MPDU Hardware Completo):**
  - Elimina los atascos y retrasos exponenciales (*Head-of-Line Blocking* / RTO) de TCP sobre redes Wi-Fi congestionadas.
  - Utiliza tráfico estándar compatible con agregación A-MPDU del router (sin saturar colas limitadas `AC_VO`), manteniendo fluidez instantánea.
* **📡 Compresión Adaptativa Inteligente (`BFP-8` / `BFP-6 Dual-Pack`):**
  - En redes limpias transmite en **BFP-8 HD** (`~800 kbps`).
  - Ante congestión en el templo baja automáticamente a **BFP-6 empaquetado doble (`62.5 pps`, ~600 kbps)** más paridad **XOR `K=4` (`78 pps` totales)** — consumiendo **60% menos paquetes/segundo** que WOB 4.0 para no ahogar el router.
  - Compresión de silencio digital a `0 bytes` de carga útil entre canciones o discursos.
* **🧬 Doble Recuperación (`XOR FEC` + `NA_NACK`) sin Tormentas de Red:**
  - Pérdidas aisladas se curan en 0–16 ms mediante XOR FEC sin pedir retransmisiones.
  - Ráfagas perdidas (`200+ ms`) activan `NA_NACK` selectivo desde un anillo caché de 2 segundos en Android antes de que suene el corte.
* **🧠 Búfer Elástico Inteligente (450 ms hasta 1,200 ms) con Reanudación Rápida:**
  - Arranca con **450 ms reales** y amplía hasta **1,200 ms** ante *jitter* elevado o ráfagas reales (expandiendo sin desafinar en pausas silenciosas).
  - Si ocurre una desconexión extrema y se vacía temporalmente, reanuda suavemente al recuperar apenas **160 ms** (*Fast-Resume*) en lugar de silenciar 1.5 segundos enteros.
* **📊 Telemetría Real (0–100%):**
  - Muestra **Búfer Real / Objetivo (`ms`)**, **Jitter Wi-Fi (`ms`)**, **Curados FEC/NACK** y **Pérdida Real (0.0–100.0%)** sin falsos `>100%`.

---

## 🛠️ Compilación desde el Código Fuente

```cmd
cd vst && build_vst.bat
cd android && build_apk.bat
```

Licencia: **MIT License**.
