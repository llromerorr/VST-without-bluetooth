#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>
#include <math.h>

#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "gdi32.lib")
#pragma comment(lib, "user32.lib")

#define kEffectMagic 0x56737450

struct AEffect;
typedef intptr_t (*audioMasterCallback)(struct AEffect*, int32_t, int32_t, intptr_t, void*, float);
typedef intptr_t (*AEffectDispatcherProc)(struct AEffect*, int32_t, int32_t, intptr_t, void*, float);
typedef void (*AEffectProcessProc)(struct AEffect*, float**, float**, int32_t);
typedef void (*AEffectSetParameterProc)(struct AEffect*, int32_t, float);
typedef float (*AEffectGetParameterProc)(struct AEffect*, int32_t);

#pragma pack(push, 8)
struct ERect {
    int16_t top;
    int16_t left;
    int16_t bottom;
    int16_t right;
};

struct AEffect {
    int32_t magic;
    AEffectDispatcherProc dispatcher;
    AEffectProcessProc process;
    AEffectSetParameterProc setParameter;
    AEffectGetParameterProc getParameter;
    int32_t numPrograms;
    int32_t numParams;
    int32_t numInputs;
    int32_t numOutputs;
    int32_t flags;
    intptr_t resvd1;
    intptr_t resvd2;
    int32_t initialDelay;
    int32_t realQualities;
    int32_t offQualities;
    float ioRatio;
    void* object;
    void* user;
    int32_t uniqueID;
    int32_t version;
    AEffectProcessProc processReplacing;
    AEffectProcessProc processDoubleReplacing;
    char future[56];
};
#pragma pack(pop)

#define DEFAULT_PORT 58200
#define PACKET_MAGIC 0x4E41 // 'NA'
#define INPUT_SAMPLE_RATE 48000.0
#define RING_BUFFER_SIZE 65536 // Power of 2 per client (~1.36s)
#define RING_BUFFER_MASK (RING_BUFFER_SIZE - 1)
#define MAX_CLIENTS 4

typedef struct {
    float left;
    float right;
} StereoSample;

typedef struct {
    uint16_t magic;
    uint16_t seq;
    int16_t pcm[480]; // 240 stereo pairs (5ms at 48k)
} AudioPacket;

typedef struct {
    uint32_t clientIP;
    uint16_t clientPort;
    char ipStr[32];
    DWORD lastSeenTick;
    bool active;
    bool prebuffering;
    float fadeVolume;

    StereoSample ringBuffer[RING_BUFFER_SIZE];
    volatile uint32_t writeIndex;
    double readPos;
} ClientStream;

typedef struct {
    SOCKET sock;
    HANDLE threadHandle;
    volatile bool running;

    ClientStream clients[MAX_CLIENTS];
    int activeClientCount;

    float hostSampleRate;
    float masterGain;
    float targetBufferMs; // Ultra-Low Latency: 25 ms (~1200 frames)

    float meterPeakL;
    float meterPeakR;

    HWND hwndEditor;
    HWND parentHwnd;
    UINT_PTR timerId;
    struct ERect editorRect;
} PluginState;

static PluginState g_state;
static struct AEffect g_effect;

static inline float softLimit(float x) {
    if (x > 1.0f) return 1.0f - expf(-x + 1.0f) * 0.3678f;
    if (x < -1.0f) return -1.0f + expf(x + 1.0f) * 0.3678f;
    return x;
}

static ClientStream* getClientStream(PluginState* state, struct sockaddr_in* addr) {
    uint32_t ip = addr->sin_addr.s_addr;
    DWORD now = GetTickCount();

    for (int i = 0; i < MAX_CLIENTS; ++i) {
        if (state->clients[i].active && state->clients[i].clientIP == ip) {
            state->clients[i].lastSeenTick = now;
            return &state->clients[i];
        }
    }

    for (int i = 0; i < MAX_CLIENTS; ++i) {
        if (!state->clients[i].active || (now - state->clients[i].lastSeenTick) > 2000) {
            ClientStream* c = &state->clients[i];
            c->clientIP = ip;
            c->clientPort = ntohs(addr->sin_port);
            inet_ntop(AF_INET, &addr->sin_addr, c->ipStr, sizeof(c->ipStr));
            c->lastSeenTick = now;
            c->writeIndex = 0;
            c->readPos = 0.0;
            c->prebuffering = true;
            c->fadeVolume = 0.0f;
            c->active = true;
            return c;
        }
    }
    return &state->clients[0];
}

static DWORD WINAPI udpReceiverThread(LPVOID param) {
    PluginState* state = (PluginState*)param;
    char buffer[1500];
    struct sockaddr_in clientAddr;

    while (state->running) {
        int clientLen = sizeof(clientAddr);
        int bytes = recvfrom(state->sock, buffer, sizeof(buffer), 0, (struct sockaddr*)&clientAddr, &clientLen);
        if (bytes <= 0) continue;

        // Auto-Discovery: Phone broadcasts "NA_DISCOVER"
        if (bytes >= 11 && memcmp(buffer, "NA_DISCOVER", 11) == 0) {
            const char* reply = "NA_OFFER";
            sendto(state->sock, reply, (int)strlen(reply), 0, (struct sockaddr*)&clientAddr, clientLen);
            continue;
        }

        if (bytes < 4) continue;
        AudioPacket* packet = (AudioPacket*)buffer;
        if (packet->magic != PACKET_MAGIC && (bytes % 4 != 0)) continue;

        ClientStream* client = getClientStream(state, &clientAddr);
        if (bytes == 4) continue; // Keep-alive

        int16_t* pcmData;
        int numSamples;
        if (packet->magic == PACKET_MAGIC) {
            pcmData = packet->pcm;
            numSamples = (bytes - 4) / sizeof(int16_t);
        } else {
            pcmData = (int16_t*)buffer;
            numSamples = bytes / sizeof(int16_t);
        }

        int numStereoFrames = numSamples / 2;
        if (numStereoFrames <= 0) continue;

        uint32_t w = client->writeIndex;
        for (int i = 0; i < numStereoFrames; ++i) {
            client->ringBuffer[w & RING_BUFFER_MASK].left = (float)pcmData[i * 2] / 32768.0f;
            client->ringBuffer[w & RING_BUFFER_MASK].right = (float)pcmData[i * 2 + 1] / 32768.0f;
            w = (w + 1) & RING_BUFFER_MASK;
        }
        MemoryBarrier();
        client->writeIndex = w;
    }
    return 0;
}

static void startNetworking(PluginState* state) {
    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return;

    state->sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (state->sock == INVALID_SOCKET) return;

    int rcvBuf = 512 * 1024;
    setsockopt(state->sock, SOL_SOCKET, SO_RCVBUF, (char*)&rcvBuf, sizeof(rcvBuf));

    DWORD timeout = 200;
    setsockopt(state->sock, SOL_SOCKET, SO_RCVTIMEO, (char*)&timeout, sizeof(timeout));

    int broadcast = 1;
    setsockopt(state->sock, SOL_SOCKET, SO_BROADCAST, (char*)&broadcast, sizeof(broadcast));

    int reuse = 1;
    setsockopt(state->sock, SOL_SOCKET, SO_REUSEADDR, (char*)&reuse, sizeof(reuse));

    struct sockaddr_in bindAddr;
    memset(&bindAddr, 0, sizeof(bindAddr));
    bindAddr.sin_family = AF_INET;
    bindAddr.sin_port = htons(DEFAULT_PORT);
    bindAddr.sin_addr.s_addr = INADDR_ANY;

    if (bind(state->sock, (struct sockaddr*)&bindAddr, sizeof(bindAddr)) == SOCKET_ERROR) {
        closesocket(state->sock);
        state->sock = INVALID_SOCKET;
        return;
    }

    state->running = true;
    state->threadHandle = CreateThread(NULL, 0, udpReceiverThread, state, 0, NULL);
}

static void stopNetworking(PluginState* state) {
    state->running = false;
    if (state->sock != INVALID_SOCKET) {
        closesocket(state->sock);
        state->sock = INVALID_SOCKET;
    }
    if (state->threadHandle) {
        WaitForSingleObject(state->threadHandle, 500);
        CloseHandle(state->threadHandle);
        state->threadHandle = NULL;
    }
    WSACleanup();
}

static void processReplacing(struct AEffect* e, float** in, float** out, int32_t frames) {
    (void)in;
    PluginState* state = (PluginState*)e->object;
    float* outL = out[0];
    float* outR = out[1];
    float gain = state->masterGain;

    DWORD now = GetTickCount();
    float hostRate = state->hostSampleRate > 1000.0f ? state->hostSampleRate : 44100.0f;
    uint32_t targetFrames = (uint32_t)(INPUT_SAMPLE_RATE * (state->targetBufferMs / 1000.0f)); // ~1200 frames (25ms)

    for (int i = 0; i < frames; ++i) {
        outL[i] = 0.0f;
        outR[i] = 0.0f;
    }

    int activeCount = 0;
    float peakL = 0.0f;
    float peakR = 0.0f;

    for (int c = 0; c < MAX_CLIENTS; ++c) {
        ClientStream* client = &state->clients[c];
        if (!client->active) continue;

        if ((now - client->lastSeenTick) > 500) {
            client->active = false;
            client->fadeVolume = 0.0f;
            continue;
        }

        activeCount++;

        uint32_t w = client->writeIndex;
        uint32_t rInt = (uint32_t)client->readPos;
        uint32_t available = (w - rInt) & RING_BUFFER_MASK;

        // Pre-buffering: wait until 25ms cushion is ready
        if (client->prebuffering) {
            if (available >= targetFrames) {
                client->prebuffering = false;
                client->readPos = (double)((w - targetFrames) & RING_BUFFER_MASK);
            } else {
                continue;
            }
        }

        // Clock Drift Micro-adjustment (tight 25ms zone)
        double driftFactor = 1.0;
        int diff = (int)available - (int)targetFrames;
        if (diff > 480) driftFactor = 1.002;
        else if (diff < -480) driftFactor = 0.998;

        double step = (INPUT_SAMPLE_RATE / (double)hostRate) * driftFactor;

        for (int i = 0; i < frames; ++i) {
            uint32_t curR = (uint32_t)client->readPos;
            uint32_t curAvail = (w - curR) & RING_BUFFER_MASK;

            if (curAvail >= 2) {
                if (client->fadeVolume < 1.0f) {
                    client->fadeVolume += (1.0f / 64.0f);
                    if (client->fadeVolume > 1.0f) client->fadeVolume = 1.0f;
                }

                uint32_t idx0 = curR & RING_BUFFER_MASK;
                uint32_t idx1 = (curR + 1) & RING_BUFFER_MASK;
                float frac = (float)(client->readPos - (double)curR);

                StereoSample s0 = client->ringBuffer[idx0];
                StereoSample s1 = client->ringBuffer[idx1];

                float smpL = (s0.left + frac * (s1.left - s0.left)) * client->fadeVolume;
                float smpR = (s0.right + frac * (s1.right - s0.right)) * client->fadeVolume;

                outL[i] += smpL;
                outR[i] += smpR;

                client->readPos += step;
                if (client->readPos >= (double)RING_BUFFER_SIZE) {
                    client->readPos -= (double)RING_BUFFER_SIZE;
                }
            } else {
                client->prebuffering = true;
                client->fadeVolume = 0.0f;
            }
        }
    }

    state->activeClientCount = activeCount;

    for (int i = 0; i < frames; ++i) {
        float l = outL[i] * gain;
        float r = outR[i] * gain;

        outL[i] = softLimit(l);
        outR[i] = softLimit(r);

        float absL = fabsf(outL[i]);
        float absR = fabsf(outR[i]);
        if (absL > peakL) peakL = absL;
        if (absR > peakR) peakR = absR;
    }

    if (peakL > state->meterPeakL) state->meterPeakL = peakL;
    else state->meterPeakL = state->meterPeakL * 0.9f + peakL * 0.1f;

    if (peakR > state->meterPeakR) state->meterPeakR = peakR;
    else state->meterPeakR = state->meterPeakR * 0.9f + peakR * 0.1f;
}

static LRESULT CALLBACK EditorWndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    PluginState* state = &g_state;
    switch (msg) {
        case WM_ERASEBKGND:
            return 1;

        case WM_PAINT: {
            PAINTSTRUCT ps;
            HDC hdc = BeginPaint(hwnd, &ps);
            RECT rc;
            GetClientRect(hwnd, &rc);

            HDC memDC = CreateCompatibleDC(hdc);
            HBITMAP memBM = CreateCompatibleBitmap(hdc, rc.right, rc.bottom);
            HBITMAP oldBM = (HBITMAP)SelectObject(memDC, memBM);

            HBRUSH bgBrush = CreateSolidBrush(RGB(20, 22, 25));
            FillRect(memDC, &rc, bgBrush);
            DeleteObject(bgBrush);

            RECT headerRc = { 0, 0, rc.right, 38 };
            HBRUSH headerBrush = CreateSolidBrush(RGB(15, 16, 18));
            FillRect(memDC, &headerRc, headerBrush);
            DeleteObject(headerBrush);

            SetBkMode(memDC, TRANSPARENT);

            HFONT hFontTitle = CreateFontA(18, 0, 0, 0, FW_BOLD, FALSE, FALSE, FALSE,
                                          DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                                          CLEARTYPE_QUALITY, DEFAULT_PITCH, "Segoe UI");
            HFONT oldFont = (HFONT)SelectObject(memDC, hFontTitle);
            SetTextColor(memDC, RGB(0, 220, 200));
            TextOutA(memDC, 16, 8, "WOB", 3);

            HFONT hFontSmall = CreateFontA(12, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE,
                                           DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                                           CLEARTYPE_QUALITY, DEFAULT_PITCH, "Segoe UI");
            SelectObject(memDC, hFontSmall);
            SetTextColor(memDC, RGB(130, 130, 140));
            TextOutA(memDC, 65, 12, "(Without Bluetooth)  v3.0 - Ultra-Low Latency", 45);

            int count = state->activeClientCount;
            HBRUSH dotBrush;
            char statusText[128];
            if (count > 0) {
                dotBrush = CreateSolidBrush(RGB(46, 213, 115));
                if (count == 1) {
                    sprintf(statusText, "CONECTADO: 1 teléfono activo (En vivo)");
                } else {
                    sprintf(statusText, "CONECTADO: %d teléfonos activos (Colaborativo)", count);
                }
                SetTextColor(memDC, RGB(46, 213, 115));
            } else {
                dotBrush = CreateSolidBrush(RGB(255, 160, 40));
                strcpy(statusText, "ESPERANDO CONEXION DESDE LA APP WOB...");
                SetTextColor(memDC, RGB(220, 170, 100));
            }

            HBRUSH oldBrush = (HBRUSH)SelectObject(memDC, dotBrush);
            HPEN nullPen = CreatePen(PS_NULL, 0, 0);
            HPEN oldPen = (HPEN)SelectObject(memDC, nullPen);
            Ellipse(memDC, 20, 52, 34, 66);
            SelectObject(memDC, oldBrush);
            DeleteObject(dotBrush);

            HFONT hFontStatus = CreateFontA(15, 0, 0, 0, FW_BOLD, FALSE, FALSE, FALSE,
                                            DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                                            CLEARTYPE_QUALITY, DEFAULT_PITCH, "Segoe UI");
            SelectObject(memDC, hFontStatus);
            TextOutA(memDC, 42, 51, statusText, (int)strlen(statusText));

            RECT boxRc = { 16, 76, rc.right - 16, 128 };
            HBRUSH boxBrush = CreateSolidBrush(RGB(28, 30, 35));
            FillRect(memDC, &boxRc, boxBrush);
            DeleteObject(boxBrush);

            SelectObject(memDC, hFontSmall);
            SetTextColor(memDC, RGB(180, 180, 190));
            TextOutA(memDC, 28, 84, "CONEXION AUTOMATICA INTELIGENTE:", 32);

            HFONT hFontSub = CreateFontA(13, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE,
                                        DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                                        CLEARTYPE_QUALITY, DEFAULT_PITCH, "Segoe UI");
            SelectObject(memDC, hFontSub);
            SetTextColor(memDC, RGB(120, 200, 180));
            TextOutA(memDC, 28, 103, "Cero configuracion. Solo toca el boton en tu telefono.", 54);

            SelectObject(memDC, hFontSmall);
            SetTextColor(memDC, RGB(130, 130, 140));
            TextOutA(memDC, 20, 142, "L:", 2);
            TextOutA(memDC, 20, 162, "R:", 2);

            int meterWidth = rc.right - 80;
            int barL = (int)(state->meterPeakL * (float)meterWidth);
            int barR = (int)(state->meterPeakR * (float)meterWidth);
            if (barL > meterWidth) barL = meterWidth;
            if (barR > meterWidth) barR = meterWidth;

            HBRUSH mBg = CreateSolidBrush(RGB(35, 38, 44));
            RECT mBgL = { 42, 144, 42 + meterWidth, 154 };
            RECT mBgR = { 42, 164, 42 + meterWidth, 174 };
            FillRect(memDC, &mBgL, mBg);
            FillRect(memDC, &mBgR, mBg);
            DeleteObject(mBg);

            HBRUSH mBar = CreateSolidBrush(RGB(0, 210, 180));
            if (barL > 0) {
                RECT rL = { 42, 144, 42 + barL, 154 };
                FillRect(memDC, &rL, mBar);
            }
            if (barR > 0) {
                RECT rR = { 42, 164, 42 + barR, 174 };
                FillRect(memDC, &rR, mBar);
            }
            DeleteObject(mBar);

            BitBlt(hdc, 0, 0, rc.right, rc.bottom, memDC, 0, 0, SRCCOPY);

            SelectObject(memDC, oldPen);
            DeleteObject(nullPen);
            SelectObject(memDC, oldFont);
            DeleteObject(hFontTitle);
            DeleteObject(hFontSmall);
            DeleteObject(hFontStatus);
            DeleteObject(hFontSub);
            SelectObject(memDC, oldBM);
            DeleteObject(memBM);
            DeleteDC(memDC);

            EndPaint(hwnd, &ps);
            return 0;
        }

        case WM_TIMER:
            InvalidateRect(hwnd, NULL, FALSE);
            return 0;

        case WM_DESTROY:
            if (state->timerId) {
                KillTimer(hwnd, state->timerId);
                state->timerId = 0;
            }
            state->hwndEditor = NULL;
            return 0;
    }
    return DefWindowProcA(hwnd, msg, wParam, lParam);
}

static intptr_t dispatcher(struct AEffect* e, int32_t op, int32_t idx, intptr_t val, void* ptr, float opt) {
    (void)idx;
    (void)val;
    PluginState* state = (PluginState*)e->object;
    switch (op) {
        case 0: return 0;
        case 1: stopNetworking(state); return 0;
        case 10:
            if (opt > 1000.0f) state->hostSampleRate = opt;
            return 0;
        case 12: return 0;
        case 13:
            state->editorRect.top = 0;
            state->editorRect.left = 0;
            state->editorRect.bottom = 188;
            state->editorRect.right = 430;
            *(struct ERect**)ptr = &state->editorRect;
            return 1;
        case 14:
            if (ptr != NULL) {
                HWND parent = (HWND)ptr;
                state->parentHwnd = parent;
                WNDCLASSA wc;
                memset(&wc, 0, sizeof(wc));
                wc.lpfnWndProc = EditorWndProc;
                wc.hInstance = GetModuleHandle(NULL);
                wc.lpszClassName = "WOBEditor";
                wc.hCursor = LoadCursor(NULL, IDC_ARROW);
                RegisterClassA(&wc);
                state->hwndEditor = CreateWindowExA(0, "WOBEditor", "WOB",
                                                    WS_CHILD | WS_VISIBLE, 0, 0, 430, 188,
                                                    parent, NULL, GetModuleHandle(NULL), NULL);
                if (state->hwndEditor) {
                    state->timerId = SetTimer(state->hwndEditor, 1, 40, NULL);
                }
                return 1;
            }
            return 0;
        case 15:
            if (state->hwndEditor) {
                DestroyWindow(state->hwndEditor);
                state->hwndEditor = NULL;
            }
            return 0;
        case 45: if (ptr) { lstrcpyA((char*)ptr, "WOB"); return 1; } break;
        case 47: if (ptr) { lstrcpyA((char*)ptr, "WOB Audio"); return 1; } break;
        case 48: if (ptr) { lstrcpyA((char*)ptr, "WOB"); return 1; } break;
        case 49: return 3000;
        case 58: return 2400;
    }
    return 0;
}

static void setParameter(struct AEffect* e, int32_t idx, float val) {
    PluginState* state = (PluginState*)e->object;
    if (idx == 0) state->masterGain = val * 2.0f;
}

static float getParameter(struct AEffect* e, int32_t idx) {
    PluginState* state = (PluginState*)e->object;
    if (idx == 0) return state->masterGain / 2.0f;
    return 0.0f;
}

__declspec(dllexport) struct AEffect* VSTPluginMain(audioMasterCallback audioMaster) {
    (void)audioMaster;
    memset(&g_state, 0, sizeof(g_state));
    g_state.hostSampleRate = 44100.0f;
    g_state.masterGain = 1.0f;
    g_state.targetBufferMs = 25.0f; // Ultra-Low Latency (~25ms, almost instant!)

    memset(&g_effect, 0, sizeof(g_effect));
    g_effect.magic = kEffectMagic;
    g_effect.dispatcher = dispatcher;
    g_effect.processReplacing = processReplacing;
    g_effect.setParameter = setParameter;
    g_effect.getParameter = getParameter;
    g_effect.numPrograms = 1;
    g_effect.numParams = 1;
    g_effect.numInputs = 0;
    g_effect.numOutputs = 2;
    g_effect.uniqueID = 0x574F4250; // 'WOBP'
    g_effect.version = 3000;
    g_effect.flags = (1 << 4) | (1 << 0);
    g_effect.object = &g_state;

    startNetworking(&g_state);

    return &g_effect;
}

BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved) {
    (void)hinstDLL;
    (void)lpvReserved;
    if (fdwReason == DLL_PROCESS_DETACH) {
        stopNetworking(&g_state);
    }
    return TRUE;
}
