#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#ifdef _MSC_VER
#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "gdi32.lib")
#pragma comment(lib, "user32.lib")
#endif

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

// =========================================================================
// WOB 5.0 Protocol & Audio Engine Constants
// =========================================================================
#define DEFAULT_PORT      58200
#define WOB5_MAGIC        0x3557  // 'W5'
#define WOB4_MAGIC        0x4E41  // 'NA' legacy

#define WOB5_TYPE_DATA    1
#define WOB5_TYPE_FEC_XOR 2
#define WOB5_TYPE_PING    3

#define CODEC_SILENCE     0
#define CODEC_BFP8        1
#define CODEC_BFP6        2
#define CODEC_PCM16       3

#define INPUT_SAMPLE_RATE 48000.0
#define FRAME_SAMPLES     384     // Exact 8.0 ms at 48kHz (24 blocks * 16 samples)
#define NUM_BLOCKS        24
#define FRAME_RING_SLOTS  512     // 512 * 8ms = 4.096s slot ring history
#define FRAME_RING_MASK   (FRAME_RING_SLOTS - 1)
#define MAX_CLIENTS       4

#define FEC_BUCKETS       16
#define FEC_MAX_K         4
#define FEC_MAX_BUF       1420

#define MIN_BUFFER_MS     160.0f
#define INIT_BUFFER_MS    220.0f
#define MAX_BUFFER_MS     850.0f

static double g_qpcFreqInvMs = 0.0;

static void initQpc(void) {
    LARGE_INTEGER freq;
    QueryPerformanceFrequency(&freq);
    if (freq.QuadPart > 0) {
        g_qpcFreqInvMs = 1000.0 / (double)freq.QuadPart;
    } else {
        g_qpcFreqInvMs = 1.0;
    }
}

static inline double getTimeMs(void) {
    LARGE_INTEGER now;
    QueryPerformanceCounter(&now);
    return (double)now.QuadPart * g_qpcFreqInvMs;
}

static inline uint16_t rd16(const uint8_t* p) {
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

static inline int16_t rds16(const uint8_t* p) {
    return (int16_t)rd16(p);
}

static inline uint32_t rd32(const uint8_t* p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static inline float softLimit(float x) {
    if (x > 1.0f) return 1.0f - expf(-x + 1.0f) * 0.3678f;
    if (x < -1.0f) return -1.0f + expf(x + 1.0f) * 0.3678f;
    return x;
}

static inline int32_t s31_div32(int32_t x) {
    return (x >= 0) ? ((x * 31) >> 5) : -(((-x) * 31) >> 5);
}

typedef struct {
    volatile uint32_t seq;
    volatile bool ready;
    float L[FRAME_SAMPLES];
    float R[FRAME_SAMPLES];
} AudioSlot;

typedef struct {
    bool active;
    uint32_t sessionTag;
    uint32_t fecBase;
    uint8_t fecK;
    bool hasParity;
    bool repaired;
    uint8_t receivedCount;
    uint16_t parityLen;
    double lastTouchMs;
    bool hasSlot[FEC_MAX_K];
    uint16_t slotLen[FEC_MAX_K];
    uint8_t slotData[FEC_MAX_K][FEC_MAX_BUF];
    uint8_t parityData[FEC_MAX_BUF];
} FecBucket;

typedef struct {
    bool active;
    uint32_t clientIP;
    uint16_t clientPort;
    struct sockaddr_in lastAddr;
    char ipStr[32];

    uint32_t sessionTag;
    volatile bool streamInit;
    volatile bool resetting;

    double lastSeenMs;
    double lastPacketArrivalMs;
    double lastStressMs;
    double lastAckMs;
    double lastNackScanMs;

    // Sequence & Playback coordinates
    volatile uint32_t highestSeq;
    volatile uint32_t earliestSeq;
    volatile uint32_t curReadSeq;
    uint32_t lastInOrderSeq;
    uint8_t lastInOrderCount;
    double readGlobalSample;

    volatile bool prebuffering;
    float fadeVolume;
    double smoothedDiffSamples;
    int consecutivePlcFrames;

    // Telemetry & Auto-adaptation
    float netTargetBufferMs;
    volatile float starveBoostMs;
    float currentJitterMs;
    uint8_t currentTier;         // 0=HD(BFP8), 1=ChurchArmored(BFP6+K3), 2=Survival(BFP6+K2)
    uint32_t packetsReceived;
    uint32_t fecRecovered;
    uint32_t nackSentCount;
    uint32_t nackRecovered;

    // Sliding 2-second loss tracking window
    uint32_t winExpected;
    uint32_t winLossEvents;
    double winStartMs;
    float lossPermille;

    // Missing frame tracking for Selective NACK
    double firstMissedMs[FRAME_RING_SLOTS];
    double lastNackMs[FRAME_RING_SLOTS];

    // PLC Pitch-matched history buffer (1024 stereo samples)
    float plcHistL[1024];
    float plcHistR[1024];
    uint32_t plcWritePos;
    uint32_t plcSynthSeq;
    float plcBufL[FRAME_SAMPLES];
    float plcBufR[FRAME_SAMPLES];

    // Silence detection cache & click-free fadeout tail
    int32_t silenceHoldSamples;
    int32_t silenceCooldownSamples;
    uint32_t lastCheckedSilSeq;
    float lastOutL;
    float lastOutR;

    // FEC recovery buckets
    FecBucket fecBuckets[FEC_BUCKETS];

    // 512 * 8ms = 4.096 seconds lock-free slot ring
    AudioSlot slots[FRAME_RING_SLOTS];

    // Legacy WOB 4.0 support counter
    uint16_t legacyWriteSeq;
    uint32_t legacySampleAcc;
} ClientStream;

typedef struct {
    SOCKET sock;
    HANDLE threadHandle;
    volatile bool running;

    ClientStream* clients;
    int activeClientCount;
    char localIpStr[32];

    float hostSampleRate;
    float masterGain;

    float meterPeakL;
    float meterPeakR;

    HWND hwndEditor;
    HWND parentHwnd;
    UINT_PTR timerId;
    struct ERect editorRect;
} PluginState;

static PluginState g_state;
static ClientStream g_clientStorage[MAX_CLIENTS];
static struct AEffect g_effect;

static void detectLocalIp(char* outIp, size_t outSize) {
    strcpy(outIp, "Automatico");
    char host[128];
    if (gethostname(host, sizeof(host)) != 0) return;
    struct addrinfo hints, *res = NULL, *p = NULL;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    if (getaddrinfo(host, NULL, &hints, &res) == 0) {
        for (p = res; p != NULL; p = p->ai_next) {
            struct sockaddr_in* ipv4 = (struct sockaddr_in*)p->ai_addr;
            char tmp[32];
            inet_ntop(AF_INET, &ipv4->sin_addr, tmp, sizeof(tmp));
            if (strncmp(tmp, "127.", 4) != 0) {
                snprintf(outIp, outSize, "%s", tmp);
                if (strncmp(tmp, "192.168.", 8) == 0 || strncmp(tmp, "10.", 3) == 0) {
                    break;
                }
            }
        }
        freeaddrinfo(res);
    }
}

static void resetClientSession(ClientStream* c, uint32_t newSessionTag, uint32_t firstSeq, double nowMs) {
    c->resetting = true;
    MemoryBarrier();

    for (int i = 0; i < FRAME_RING_SLOTS; ++i) {
        c->slots[i].ready = false;
        c->slots[i].seq = 0;
        c->firstMissedMs[i] = 0.0;
        c->lastNackMs[i] = 0.0;
    }
    for (int i = 0; i < FEC_BUCKETS; ++i) {
        c->fecBuckets[i].active = false;
    }
    memset(c->plcHistL, 0, sizeof(c->plcHistL));
    memset(c->plcHistR, 0, sizeof(c->plcHistR));
    c->plcWritePos = 0;
    c->plcSynthSeq = 0xFFFFFFFFu;
    c->silenceHoldSamples = 0;
    c->silenceCooldownSamples = 0;
    c->lastCheckedSilSeq = 0xFFFFFFFFu;
    c->lastOutL = 0.0f;
    c->lastOutR = 0.0f;

    c->sessionTag = newSessionTag;
    c->highestSeq = firstSeq;
    c->earliestSeq = firstSeq;
    c->curReadSeq = firstSeq;
    c->lastInOrderSeq = firstSeq;
    c->lastInOrderCount = 1;
    c->readGlobalSample = (double)firstSeq * (double)FRAME_SAMPLES;

    c->prebuffering = true;
    c->fadeVolume = 0.0f;
    c->smoothedDiffSamples = 0.0;
    c->consecutivePlcFrames = 0;

    c->lastPacketArrivalMs = 0.0;
    c->lastStressMs = nowMs;
    c->lastAckMs = nowMs;
    c->lastNackScanMs = nowMs;
    c->netTargetBufferMs = INIT_BUFFER_MS;
    c->starveBoostMs = INIT_BUFFER_MS;
    c->currentJitterMs = 0.0f;
    c->currentTier = 0;
    c->packetsReceived = 0;
    c->fecRecovered = 0;
    c->nackSentCount = 0;
    c->nackRecovered = 0;
    c->winExpected = 0;
    c->winLossEvents = 0;
    c->winStartMs = nowMs;
    c->lossPermille = 0.0f;
    c->legacyWriteSeq = 0;
    c->legacySampleAcc = 0;

    MemoryBarrier();
    c->streamInit = true;
    c->resetting = false;
}

static ClientStream* getClientStream(PluginState* state, struct sockaddr_in* addr, double nowMs) {
    uint32_t ip = addr->sin_addr.s_addr;

    for (int i = 0; i < MAX_CLIENTS; ++i) {
        if (state->clients[i].active && state->clients[i].clientIP == ip) {
            state->clients[i].lastSeenMs = nowMs;
            state->clients[i].clientPort = ntohs(addr->sin_port);
            state->clients[i].lastAddr = *addr;
            return &state->clients[i];
        }
    }

    for (int i = 0; i < MAX_CLIENTS; ++i) {
        if (!state->clients[i].active || (nowMs - state->clients[i].lastSeenMs) > 5000.0) {
            ClientStream* c = &state->clients[i];
            memset(c, 0, sizeof(ClientStream));
            c->clientIP = ip;
            c->clientPort = ntohs(addr->sin_port);
            c->lastAddr = *addr;
            inet_ntop(AF_INET, &addr->sin_addr, c->ipStr, sizeof(c->ipStr));
            c->lastSeenMs = nowMs;
            c->netTargetBufferMs = INIT_BUFFER_MS;
            c->starveBoostMs = INIT_BUFFER_MS;
            c->prebuffering = true;
            c->active = true;
            return c;
        }
    }
    return &state->clients[0];
}

// =========================================================================
// BFP-8 / BFP-6 Decoder (Stateless per 8ms Frame, Bit-Exact with Android)
// =========================================================================
static void decodeBfp8Channel(const int16_t s0, const uint8_t* shiftBytes, const int8_t* qBytes, float* out) {
    out[0] = (float)s0 / 32768.0f;
    int32_t prev = (int32_t)s0;

    for (int b = 0; b < NUM_BLOCKS; ++b) {
        int shift = (b & 1) ? ((shiftBytes[b >> 1] >> 4) & 0x0F) : (shiftBytes[b >> 1] & 0x0F);
        if (shift > 12) shift = 12;

        int start = b * 16;
        int end = start + 16;
        int first = (b == 0) ? 1 : start;

        for (int i = first; i < end; ++i) {
            int32_t pred = s31_div32(prev);
            int32_t rec = pred + ((int32_t)qBytes[i] << shift);
            if (rec > 32767) rec = 32767;
            if (rec < -32768) rec = -32768;
            prev = rec;
            out[i] = (float)rec / 32768.0f;
        }
    }
}

static void decodeBfp6Channel(const int16_t s0, const uint8_t* shiftBytes, const uint8_t* packed, float* out) {
    int32_t qs[FRAME_SAMPLES];
    int j = 0;
    for (int i = 0; i < FRAME_SAMPLES; i += 4) {
        uint8_t b0 = packed[j];
        uint8_t b1 = packed[j + 1];
        uint8_t b2 = packed[j + 2];
        qs[i]     = (int32_t)(b0 & 0x3F) - 32;
        qs[i + 1] = (int32_t)(((b0 >> 6) & 0x03) | ((b1 & 0x0F) << 2)) - 32;
        qs[i + 2] = (int32_t)(((b1 >> 4) & 0x0F) | ((b2 & 0x03) << 4)) - 32;
        qs[i + 3] = (int32_t)((b2 >> 2) & 0x3F) - 32;
        j += 3;
    }

    out[0] = (float)s0 / 32768.0f;
    int32_t prev = (int32_t)s0;

    for (int b = 0; b < NUM_BLOCKS; ++b) {
        int shift = (b & 1) ? ((shiftBytes[b >> 1] >> 4) & 0x0F) : (shiftBytes[b >> 1] & 0x0F);
        if (shift > 12) shift = 12;

        int start = b * 16;
        int end = start + 16;
        int first = (b == 0) ? 1 : start;

        for (int i = first; i < end; ++i) {
            int32_t pred = s31_div32(prev);
            int32_t rec = pred + (qs[i] << shift);
            if (rec > 32767) rec = 32767;
            if (rec < -32768) rec = -32768;
            prev = rec;
            out[i] = (float)rec / 32768.0f;
        }
    }
}

static bool writeDecodedFrameToSlot(ClientStream* c, uint32_t frameSeq, uint8_t codec, const uint8_t* frameData, int frameLen, bool isRecoveredNack) {
    uint32_t slotIdx = frameSeq & FRAME_RING_MASK;
    AudioSlot* slot = &c->slots[slotIdx];

    // Idempotent check: already have this exact frame
    if (slot->ready && slot->seq == frameSeq) {
        return false;
    }

    if (codec == CODEC_SILENCE) {
        memset(slot->L, 0, sizeof(slot->L));
        memset(slot->R, 0, sizeof(slot->R));
    } else if (codec == CODEC_BFP8) {
        if (frameLen != 796) return false;
        int16_t l0 = rds16(frameData);
        int16_t r0 = rds16(frameData + 2);
        const uint8_t* shiftsL = frameData + 4;
        const uint8_t* shiftsR = frameData + 16;
        const int8_t* qL = (const int8_t*)(frameData + 28);
        const int8_t* qR = (const int8_t*)(frameData + 412);
        decodeBfp8Channel(l0, shiftsL, qL, slot->L);
        decodeBfp8Channel(r0, shiftsR, qR, slot->R);
    } else if (codec == CODEC_BFP6) {
        if (frameLen != 604) return false;
        int16_t l0 = rds16(frameData);
        int16_t r0 = rds16(frameData + 2);
        const uint8_t* shiftsL = frameData + 4;
        const uint8_t* shiftsR = frameData + 16;
        const uint8_t* pL = frameData + 28;
        const uint8_t* pR = frameData + 316;
        decodeBfp6Channel(l0, shiftsL, pL, slot->L);
        decodeBfp6Channel(r0, shiftsR, pR, slot->R);
    } else if (codec == CODEC_PCM16) {
        if (frameLen != FRAME_SAMPLES * 4) return false;
        for (int i = 0; i < FRAME_SAMPLES; ++i) {
            slot->L[i] = (float)rds16(frameData + i * 4) / 32768.0f;
            slot->R[i] = (float)rds16(frameData + i * 4 + 2) / 32768.0f;
        }
    } else {
        return false;
    }

    slot->seq = frameSeq;
    MemoryBarrier();
    slot->ready = true;
    c->firstMissedMs[slotIdx] = 0.0;
    if (isRecoveredNack) {
        c->nackRecovered++;
    }
    return true;
}

static void recordLossAndExpandCushion(ClientStream* c, double nowMs, float amountMs, float capMs) {
    c->winLossEvents++;
    c->lastStressMs = nowMs;
    if (c->netTargetBufferMs < capMs) {
        c->netTargetBufferMs += amountMs;
        if (c->netTargetBufferMs > capMs) c->netTargetBufferMs = capMs;
    }
}

static bool processWob5DataPayload(ClientStream* c, uint8_t flags, uint32_t frameSeq, const uint8_t* payload, uint16_t payloadLen, double nowMs, bool fromFec) {
    uint8_t codec = flags & 0x07;
    uint8_t frameCount = ((flags >> 3) & 0x03);
    if (frameCount < 1) frameCount = 1;
    if (frameCount > 2) return false;
    bool isRetransmit = ((flags >> 5) & 0x01) != 0;
    uint8_t tier = (flags >> 6) & 0x03;
    c->currentTier = tier;

    // Check if frame is already way in the past relative to playback
    if (c->streamInit && !c->prebuffering) {
        uint32_t curR = c->curReadSeq;
        if (frameSeq + frameCount <= curR && (curR - (frameSeq + frameCount)) < 10000u) {
            return false; // Too late, discard cleanly without warping timing
        }
    }

    int expectedPerFrame = 0;
    if (codec == CODEC_SILENCE) expectedPerFrame = 0;
    else if (codec == CODEC_BFP8) expectedPerFrame = 796;
    else if (codec == CODEC_BFP6) expectedPerFrame = 604;
    else if (codec == CODEC_PCM16) expectedPerFrame = FRAME_SAMPLES * 4;
    else return false;

    if ((int)payloadLen != frameCount * expectedPerFrame) {
        return false;
    }

    bool burstGapDetected = false;

    // Update highestSeq and track any missing sequence holes
    if (!c->streamInit) {
        c->highestSeq = frameSeq + frameCount - 1;
        c->streamInit = true;
    } else if (frameSeq + frameCount - 1 > c->highestSeq) {
        uint32_t prevHigh = c->highestSeq;
        uint32_t gap = frameSeq - (prevHigh + 1);
        if (gap > 0 && gap <= 64) {
            if (gap >= 3) burstGapDetected = true;
            for (uint32_t s = prevHigh + 1; s < frameSeq; ++s) {
                uint32_t sl = s & FRAME_RING_MASK;
                if (!(c->slots[sl].ready && c->slots[sl].seq == s)) {
                    if (c->firstMissedMs[sl] == 0.0) {
                        // Burst gaps (3+ frames) cannot be solved by 1-pkt XOR FEC -> schedule instant 0ms NACK!
                        c->firstMissedMs[sl] = (gap >= 3) ? (nowMs - 25.0) : nowMs;
                        c->lastNackMs[sl] = 0.0;
                    }
                }
            }
        }
        c->winExpected += (gap < 64) ? (gap + frameCount) : frameCount;
        c->highestSeq = frameSeq + frameCount - 1;
    }

    for (uint8_t f = 0; f < frameCount; ++f) {
        writeDecodedFrameToSlot(c, frameSeq + f, codec, payload + f * expectedPerFrame, expectedPerFrame, isRetransmit);
    }

    // High-precision jitter measurement only on sequential fresh DATA packets
    if (!isRetransmit && !fromFec) {
        if (c->lastPacketArrivalMs > 0.0 && frameSeq == c->lastInOrderSeq + c->lastInOrderCount) {
            double deltaMs = nowMs - c->lastPacketArrivalMs;
            double expectedMs = (double)c->lastInOrderCount * 8.0;
            float instantJitter = (float)fabs(deltaMs - expectedMs);
            c->currentJitterMs = c->currentJitterMs * 0.90f + instantJitter * 0.10f;

            if (instantJitter > 14.0f) {
                recordLossAndExpandCushion(c, nowMs, 35.0f, 550.0f);
            }
        }
        c->lastPacketArrivalMs = nowMs;
        c->lastInOrderSeq = frameSeq;
        c->lastInOrderCount = frameCount;
    }
    return burstGapDetected;
}

// =========================================================================
// XOR Parity FEC Recovery (0-ms / zero-NACK recovery for 1-in-K drops)
// =========================================================================
static FecBucket* findOrAllocFecBucket(ClientStream* c, uint32_t sessionTag, uint32_t fecBase, uint8_t fecK, double nowMs) {
    int oldestIdx = 0;
    double oldestTime = 1e18;

    for (int i = 0; i < FEC_BUCKETS; ++i) {
        FecBucket* b = &c->fecBuckets[i];
        if (b->active && b->sessionTag == sessionTag && b->fecBase == fecBase && b->fecK == fecK) {
            b->lastTouchMs = nowMs;
            return b;
        }
        if (!b->active) {
            oldestIdx = i;
            oldestTime = -1.0;
        } else if (oldestTime >= 0.0 && b->lastTouchMs < oldestTime) {
            oldestTime = b->lastTouchMs;
            oldestIdx = i;
        }
    }

    FecBucket* nb = &c->fecBuckets[oldestIdx];
    memset(nb, 0, sizeof(FecBucket));
    nb->active = true;
    nb->sessionTag = sessionTag;
    nb->fecBase = fecBase;
    nb->fecK = fecK;
    nb->lastTouchMs = nowMs;
    return nb;
}

static void tryRecoverFecBucket(ClientStream* c, FecBucket* b, double nowMs) {
    if (b->repaired || !b->hasParity || b->receivedCount != (b->fecK - 1)) return;
    if (b->parityLen < 7 || b->parityLen > FEC_MAX_BUF) return;

    int missingIdx = -1;
    for (int i = 0; i < b->fecK; ++i) {
        if (!b->hasSlot[i]) { missingIdx = i; break; }
    }
    if (missingIdx < 0) return;

    uint8_t recBuf[FEC_MAX_BUF];
    memcpy(recBuf, b->parityData, b->parityLen);

    for (int i = 0; i < b->fecK; ++i) {
        if (i == missingIdx) continue;
        uint16_t slen = b->slotLen[i];
        if (slen > b->parityLen) slen = b->parityLen;
        for (uint16_t k = 0; k < slen; ++k) {
            recBuf[k] ^= b->slotData[i][k];
        }
    }

    uint8_t recFlags = recBuf[0];
    uint32_t recFrameSeq = rd32(recBuf + 1);
    uint16_t recPayloadLen = rd16(recBuf + 5);

    b->repaired = true;
    if (recPayloadLen + 7 <= b->parityLen && recPayloadLen <= 1360) {
        c->fecRecovered++;
        recordLossAndExpandCushion(c, nowMs, 8.0f, 360.0f);
        processWob5DataPayload(c, recFlags, recFrameSeq, recBuf + 7, recPayloadLen, nowMs, true);
    }
}

static void scanAndSendNacks(ClientStream* c, SOCKET sock, double nowMs) {
    if (!c->streamInit || c->highestSeq <= c->curReadSeq + 1) return;

    uint32_t curR = c->curReadSeq;
    uint32_t start = curR + 1;
    if (c->highestSeq > 96 && c->highestSeq - start > 96) {
        start = c->highestSeq - 96;
    }

    uint32_t baseSeq = 0;
    bool foundBase = false;
    for (uint32_t s = start; s <= c->highestSeq; ++s) {
        uint32_t sl = s & FRAME_RING_MASK;
        if (!(c->slots[sl].ready && c->slots[sl].seq == s)) {
            if (c->firstMissedMs[sl] > 0.0 && (nowMs - c->firstMissedMs[sl]) >= 20.0) {
                if (c->lastNackMs[sl] == 0.0 || (nowMs - c->lastNackMs[sl]) >= 32.0) {
                    baseSeq = s;
                    foundBase = true;
                    break;
                }
            }
        }
    }

    if (!foundBase) return;

    uint32_t mask = 0;
    for (int k = 0; k < 32; ++k) {
        uint32_t s = baseSeq + (uint32_t)k;
        if (s > c->highestSeq) break;
        uint32_t sl = s & FRAME_RING_MASK;
        if (!(c->slots[sl].ready && c->slots[sl].seq == s)) {
            if (c->firstMissedMs[sl] > 0.0 && (nowMs - c->firstMissedMs[sl]) >= 16.0) {
                if (c->lastNackMs[sl] == 0.0 || (nowMs - c->lastNackMs[sl]) >= 32.0) {
                    mask |= (1u << k);
                    c->lastNackMs[sl] = nowMs;
                }
            }
        }
    }

    if (mask != 0) {
        char nackMsg[64];
        snprintf(nackMsg, sizeof(nackMsg), "NA_NACK:%u:%08X", (unsigned int)baseSeq, (unsigned int)mask);
        sendto(sock, nackMsg, (int)strlen(nackMsg), 0, (struct sockaddr*)&c->lastAddr, sizeof(c->lastAddr));
        c->nackSentCount++;
        recordLossAndExpandCushion(c, nowMs, 45.0f, 650.0f);
    }
}

static void sendAckTelemetry(ClientStream* c, SOCKET sock, double nowMs) {
    float effTarget = (c->starveBoostMs > c->netTargetBufferMs) ? c->starveBoostMs : c->netTargetBufferMs;
    if (effTarget > MAX_BUFFER_MS) effTarget = MAX_BUFFER_MS;

    if ((nowMs - c->winStartMs) >= 2000.0) {
        if (c->winExpected > 10) {
            float perm = ((float)c->winLossEvents * 1000.0f) / (float)c->winExpected;
            c->lossPermille = c->lossPermille * 0.5f + perm * 0.5f;
        } else {
            c->lossPermille *= 0.5f;
        }
        c->winExpected = 0;
        c->winLossEvents = 0;
        c->winStartMs = nowMs;
    }

    char ackMsg[128];
    snprintf(ackMsg, sizeof(ackMsg), "NA_ACK:%u:%.0f:%.1f:%.1f:%u:%u",
             (unsigned int)c->packetsReceived,
             effTarget,
             c->lossPermille,
             c->currentJitterMs,
             (unsigned int)c->fecRecovered,
             (unsigned int)c->nackRecovered);
    sendto(sock, ackMsg, (int)strlen(ackMsg), 0, (struct sockaddr*)&c->lastAddr, sizeof(c->lastAddr));
    c->lastAckMs = nowMs;
}

static DWORD WINAPI udpReceiverThread(LPVOID param) {
    PluginState* state = (PluginState*)param;
    SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_HIGHEST);

    uint8_t buffer[2048];
    struct sockaddr_in clientAddr;

    while (state->running) {
        int clientLen = sizeof(clientAddr);
        int bytes = recvfrom(state->sock, (char*)buffer, sizeof(buffer), 0, (struct sockaddr*)&clientAddr, &clientLen);
        double nowMs = getTimeMs();

        if (bytes > 0) {
            // 1. Discovery Handshake
            if (bytes >= 11 && memcmp(buffer, "NA_DISCOVER", 11) == 0) {
                const char* reply = "NA_OFFER:WOB5";
                sendto(state->sock, reply, (int)strlen(reply), 0, (struct sockaddr*)&clientAddr, clientLen);
                continue;
            }

            if (bytes >= 4) {
                uint16_t magic = rd16(buffer);
                ClientStream* c = getClientStream(state, &clientAddr, nowMs);

                // =========================================================
                // WOB 5.0 Packet (magic == 0x3557, 20-byte header)
                // =========================================================
                if (magic == WOB5_MAGIC && bytes >= 20) {
                    uint8_t type = buffer[2];
                    uint8_t flags = buffer[3];
                    uint32_t frameSeq = rd32(buffer + 4);
                    uint32_t fecBase = rd32(buffer + 8);
                    uint8_t fecIndex = buffer[12];
                    uint8_t fecK = buffer[13];
                    uint16_t payloadLen = rd16(buffer + 14);
                    uint32_t sessionTag = rd32(buffer + 16);

                    if (!c->streamInit || c->sessionTag != sessionTag) {
                        resetClientSession(c, sessionTag, frameSeq, nowMs);
                    }

                    if (type == WOB5_TYPE_PING) {
                        sendAckTelemetry(c, state->sock, nowMs);
                        continue;
                    }

                    if (20 + (int)payloadLen > bytes) continue;
                    const uint8_t* payload = buffer + 20;

                    if (type == WOB5_TYPE_DATA) {
                        c->packetsReceived++;

                        // Store in XOR FEC bucket if FEC is enabled
                        if (fecK >= 2 && fecK <= FEC_MAX_K && fecIndex < fecK && ((flags & 0x20) == 0)) {
                            if (7 + payloadLen <= FEC_MAX_BUF) {
                                FecBucket* b = findOrAllocFecBucket(c, sessionTag, fecBase, fecK, nowMs);
                                if (!b->hasSlot[fecIndex]) {
                                    b->slotData[fecIndex][0] = flags;
                                    memcpy(b->slotData[fecIndex] + 1, buffer + 4, 4); // frameSeq LE
                                    memcpy(b->slotData[fecIndex] + 5, buffer + 14, 2); // payloadLen LE
                                    if (payloadLen > 0) {
                                        memcpy(b->slotData[fecIndex] + 7, payload, payloadLen);
                                    }
                                    b->slotLen[fecIndex] = (uint16_t)(7 + payloadLen);
                                    b->hasSlot[fecIndex] = true;
                                    b->receivedCount++;
                                    tryRecoverFecBucket(c, b, nowMs);
                                }
                            }
                        }

                        if (processWob5DataPayload(c, flags, frameSeq, payload, payloadLen, nowMs, false)) {
                            // Burst gap detected! Dispatch selective NACK immediately (0ms delay)!
                            c->lastNackScanMs = nowMs;
                            scanAndSendNacks(c, state->sock, nowMs);
                        }
                    } else if (type == WOB5_TYPE_FEC_XOR) {
                        if (fecK >= 2 && fecK <= FEC_MAX_K && payloadLen >= 7 && payloadLen <= FEC_MAX_BUF) {
                            FecBucket* b = findOrAllocFecBucket(c, sessionTag, fecBase, fecK, nowMs);
                            memcpy(b->parityData, payload, payloadLen);
                            b->parityLen = payloadLen;
                            b->hasParity = true;
                            tryRecoverFecBucket(c, b, nowMs);
                        }
                    }
                }
                // =========================================================
                // Legacy WOB 4.0 Support (magic == 0x4E41)
                // =========================================================
                else if (magic == WOB4_MAGIC) {
                    if (bytes == 4) {
                        sendAckTelemetry(c, state->sock, nowMs);
                        continue;
                    }
                    int samples = (bytes - 4) / 2;
                    int frames = samples / 2; // usually 240
                    if (frames > 0 && frames <= FRAME_SAMPLES) {
                        if (!c->streamInit || c->sessionTag != 0x4E410000u) {
                            resetClientSession(c, 0x4E410000u, 0, nowMs);
                        }
                        c->packetsReceived++;
                        const int16_t* pcm = (const int16_t*)(buffer + 4);
                        for (int i = 0; i < frames; ++i) {
                            uint32_t seq = c->legacySampleAcc / FRAME_SAMPLES;
                            uint32_t off = c->legacySampleAcc % FRAME_SAMPLES;
                            uint32_t sl = seq & FRAME_RING_MASK;
                            c->slots[sl].L[off] = (float)pcm[i * 2] / 32768.0f;
                            c->slots[sl].R[off] = (float)pcm[i * 2 + 1] / 32768.0f;
                            if (off == FRAME_SAMPLES - 1) {
                                c->slots[sl].seq = seq;
                                MemoryBarrier();
                                c->slots[sl].ready = true;
                                c->highestSeq = seq;
                            }
                            c->legacySampleAcc++;
                        }
                    }
                }
            }
        }

        // Periodic telemetry ACKs, NACK scanning & cautious cushion relaxation across active clients
        for (int i = 0; i < MAX_CLIENTS; ++i) {
            ClientStream* c = &state->clients[i];
            if (!c->active || !c->streamInit) continue;

            if ((nowMs - c->lastNackScanMs) >= 15.0) {
                c->lastNackScanMs = nowMs;
                scanAndSendNacks(c, state->sock, nowMs);
            }

            if ((nowMs - c->lastAckMs) >= 220.0) {
                sendAckTelemetry(c, state->sock, nowMs);
            }

            // Very gradual relaxation ONLY after 30+ seconds of zero stress/loss
            if ((nowMs - c->lastStressMs) > 30000.0 && c->lossPermille < 1.0f && c->currentJitterMs < 8.0f) {
                if (c->netTargetBufferMs > 180.0f) {
                    c->netTargetBufferMs -= 0.015f;
                    if (c->netTargetBufferMs < 180.0f) c->netTargetBufferMs = 180.0f;
                }
                if (c->starveBoostMs > 180.0f) {
                    c->starveBoostMs -= 0.015f;
                    if (c->starveBoostMs < 180.0f) c->starveBoostMs = 180.0f;
                }
            }
        }
    }
    return 0;
}

static void startNetworking(PluginState* state) {
    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return;

    detectLocalIp(state->localIpStr, sizeof(state->localIpStr));

    state->sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (state->sock == INVALID_SOCKET) return;

    int rcvBuf = 2 * 1024 * 1024;
    setsockopt(state->sock, SOL_SOCKET, SO_RCVBUF, (char*)&rcvBuf, sizeof(rcvBuf));

    int sndBuf = 512 * 1024;
    setsockopt(state->sock, SOL_SOCKET, SO_SNDBUF, (char*)&sndBuf, sizeof(sndBuf));

    DWORD timeout = 15; // 15ms polling interval for fast NACK recovery
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

// =========================================================================
// Pitch-Matched Packet Loss Concealment (PLC)
// =========================================================================
static void synthesizePlcFrame(ClientStream* c, uint32_t missingSeq) {
    c->plcSynthSeq = missingSeq;

    // Quick pitch lag search (64..200 samples -> 240Hz..750Hz) over recent clean history
    uint32_t w = c->plcWritePos;
    int bestLag = 128;
    double bestScore = -1e18;

    for (int lag = 64; lag <= 192; lag += 2) {
        double corr = 0.0;
        for (int i = 0; i < 96; i += 2) {
            float a = c->plcHistL[(w - 1 - i) & 1023];
            float b = c->plcHistL[(w - 1 - i - lag) & 1023];
            corr += (double)a * (double)b;
        }
        if (corr > bestScore) {
            bestScore = corr;
            bestLag = lag;
        }
    }

    // Check if future slot (missingSeq + 1) exists for clean tail crossfade
    AudioSlot* nextSlot = &c->slots[(missingSeq + 1) & FRAME_RING_MASK];
    bool hasNext = (nextSlot->ready && nextSlot->seq == missingSeq + 1);

    float damp = 0.94f;
    for (int k = 0; k < c->consecutivePlcFrames; ++k) damp *= 0.85f;

    uint32_t srcStart = w - (uint32_t)bestLag;
    for (int i = 0; i < FRAME_SAMPLES; ++i) {
        uint32_t idx = (srcStart + (uint32_t)(i % bestLag)) & 1023;
        float sL = c->plcHistL[idx] * damp;
        float sR = c->plcHistR[idx] * damp;

        // Smooth 32-sample crossfade into next packet start if already present!
        if (hasNext && i >= FRAME_SAMPLES - 32) {
            float t = (float)(i - (FRAME_SAMPLES - 32)) / 32.0f;
            int nIdx = i - (FRAME_SAMPLES - 32);
            sL = sL * (1.0f - t) + nextSlot->L[nIdx] * t;
            sR = sR * (1.0f - t) + nextSlot->R[nIdx] * t;
        }

        c->plcBufL[i] = sL;
        c->plcBufR[i] = sR;
    }
}

static inline void readStreamSample(ClientStream* c, uint64_t sampleIdx, float* outL, float* outR) {
    uint32_t seq = (uint32_t)(sampleIdx / FRAME_SAMPLES);
    uint32_t off = (uint32_t)(sampleIdx % FRAME_SAMPLES);
    AudioSlot* s = &c->slots[seq & FRAME_RING_MASK];

    if (s->ready && s->seq == seq) {
        *outL = s->L[off];
        *outR = s->R[off];
    } else if (c->plcSynthSeq == seq) {
        *outL = c->plcBufL[off];
        *outR = c->plcBufR[off];
    } else {
        *outL = 0.0f;
        *outR = 0.0f;
    }
}

static void processReplacing(struct AEffect* e, float** in, float** out, int32_t frames) {
    (void)in;
    PluginState* state = (PluginState*)e->object;
    float* outL = out[0];
    float* outR = out[1];
    float gain = state->masterGain;

    for (int i = 0; i < frames; ++i) {
        outL[i] = 0.0f;
        outR[i] = 0.0f;
    }

    double nowMs = getTimeMs();
    float hostRate = state->hostSampleRate > 1000.0f ? state->hostSampleRate : 44100.0f;
    int activeCount = 0;
    float peakL = 0.0f;
    float peakR = 0.0f;

    for (int ci = 0; ci < MAX_CLIENTS; ++ci) {
        ClientStream* c = &state->clients[ci];
        if (!c->active) continue;

        if ((nowMs - c->lastSeenMs) > 5000.0) {
            c->active = false;
            c->fadeVolume = 0.0f;
            continue;
        }

        activeCount++;
        if (!c->streamInit || c->resetting) continue;

        float effTargetMs = (c->starveBoostMs > c->netTargetBufferMs) ? c->starveBoostMs : c->netTargetBufferMs;
        if (effTargetMs < MIN_BUFFER_MS) effTargetMs = MIN_BUFFER_MS;
        if (effTargetMs > MAX_BUFFER_MS) effTargetMs = MAX_BUFFER_MS;
        uint32_t targetSamples = (uint32_t)(INPUT_SAMPLE_RATE * (effTargetMs / 1000.0f));

        uint32_t hiSeq = c->highestSeq;
        uint64_t hiSampleEnd = ((uint64_t)hiSeq + 1ULL) * (uint64_t)FRAME_SAMPLES;

        // Pre-buffering / Clean Re-buffering State
        if (c->prebuffering) {
            uint32_t prebufGoal = (c->packetsReceived < 50 && c->starveBoostMs <= INIT_BUFFER_MS)
                                ? (uint32_t)(INPUT_SAMPLE_RATE * (INIT_BUFFER_MS / 1000.0f))
                                : targetSamples;
            if (hiSampleEnd > (uint64_t)c->readGlobalSample) {
                uint64_t avail = hiSampleEnd - (uint64_t)c->readGlobalSample;
                if (avail >= (uint64_t)prebufGoal) {
                    uint64_t anchor = hiSampleEnd - (uint64_t)prebufGoal;
                    uint32_t anchorSeq = (uint32_t)(anchor / FRAME_SAMPLES);
                    // Wait until at least 2 consecutive slots at playhead are present
                    if (c->slots[anchorSeq & FRAME_RING_MASK].ready && c->slots[anchorSeq & FRAME_RING_MASK].seq == anchorSeq &&
                        c->slots[(anchorSeq + 1) & FRAME_RING_MASK].ready && c->slots[(anchorSeq + 1) & FRAME_RING_MASK].seq == anchorSeq + 1) {
                        c->readGlobalSample = (double)anchor;
                        c->curReadSeq = anchorSeq;
                        c->prebuffering = false;
                        c->smoothedDiffSamples = 0.0;
                        c->consecutivePlcFrames = 0;
                    } else if (avail > (uint64_t)prebufGoal + FRAME_SAMPLES * 4ULL) {
                        // Skip over initial hole if frames ahead already piled up
                        c->readGlobalSample = (double)anchor;
                        c->curReadSeq = anchorSeq;
                        c->prebuffering = false;
                        c->smoothedDiffSamples = 0.0;
                        c->consecutivePlcFrames = 0;
                    }
                }
            }
            continue;
        }

        // Trim runaway stale latency (> target + 550ms backlog)
        if (hiSampleEnd > (uint64_t)c->readGlobalSample) {
            double availNow = (double)hiSampleEnd - c->readGlobalSample;
            if (availNow > (double)targetSamples + INPUT_SAMPLE_RATE * 0.55) {
                c->readGlobalSample = (double)(hiSampleEnd - (uint64_t)targetSamples);
                c->fadeVolume = 0.0f; // brief soft crossfade-in on jump
            }
        }

        // Smooth clock drift steering (+0.5% catch-up, -0.8% imperceptible cushion growth)
        double availSamples = (hiSampleEnd > (uint64_t)c->readGlobalSample) ? ((double)hiSampleEnd - c->readGlobalSample) : 0.0;
        double diff = availSamples - (double)targetSamples;
        c->smoothedDiffSamples = c->smoothedDiffSamples * 0.992 + diff * 0.008;

        double driftFactor = 1.0;
        if (fabs(c->smoothedDiffSamples) > 480.0) {
            driftFactor = 1.0 + (c->smoothedDiffSamples / (double)targetSamples) * 0.012;
            if (driftFactor > 1.005) driftFactor = 1.005;
            if (driftFactor < 0.992) driftFactor = 0.992;
        }
        double step = (INPUT_SAMPLE_RATE / (double)hostRate) * driftFactor;

        for (int i = 0; i < frames; ++i) {
            if (c->resetting) break;

            if (c->silenceHoldSamples > 0) {
                c->silenceHoldSamples--;
                c->smoothedDiffSamples += 1.0;
                c->lastOutL *= 0.96f;
                c->lastOutR *= 0.96f;
                outL[i] += c->lastOutL;
                outR[i] += c->lastOutR;
                continue;
            }
            if (c->silenceCooldownSamples > 0) {
                c->silenceCooldownSamples--;
            }

            uint64_t curSampleIdx = (uint64_t)c->readGlobalSample;
            uint32_t seq = (uint32_t)(curSampleIdx / FRAME_SAMPLES);
            c->curReadSeq = seq;

            // If we need to grow cushion (diff < -20ms) and current frame is digital silence/quiet gap, pause 1 slot cleanly!
            AudioSlot* curSlot = &c->slots[seq & FRAME_RING_MASK];
            if (seq != c->lastCheckedSilSeq) {
                c->lastCheckedSilSeq = seq;
                if (c->silenceCooldownSamples <= 0 && c->smoothedDiffSamples < -960.0 && curSlot->ready && curSlot->seq == seq) {
                    float maxAmp = 0.0f;
                    for (int k = 0; k < FRAME_SAMPLES; k += 8) {
                        float al = fabsf(curSlot->L[k]);
                        float ar = fabsf(curSlot->R[k]);
                        if (al > maxAmp) maxAmp = al;
                        if (ar > maxAmp) maxAmp = ar;
                    }
                    if (maxAmp < 0.0025f) {
                        c->silenceHoldSamples = FRAME_SAMPLES;
                        c->silenceCooldownSamples = FRAME_SAMPLES * 3;
                        continue; // Gains +8ms pure cushion in silence with ZERO audio impact!
                    }
                }
            }

            if (curSampleIdx >= hiSampleEnd) {
                // Buffer starved! Smooth exponential decay -> Rebuffer cleanly instead of stuttering!
                if (c->starveBoostMs < MAX_BUFFER_MS) {
                    c->starveBoostMs += 120.0f;
                    if (c->starveBoostMs > MAX_BUFFER_MS) c->starveBoostMs = MAX_BUFFER_MS;
                }
                if (c->fadeVolume > 0.005f) {
                    c->fadeVolume *= 0.97f;
                    c->lastOutL *= 0.985f;
                    c->lastOutR *= 0.985f;
                    outL[i] += c->lastOutL * c->fadeVolume;
                    outR[i] += c->lastOutR * c->fadeVolume;
                } else {
                    c->fadeVolume = 0.0f;
                    c->lastOutL = 0.0f;
                    c->lastOutR = 0.0f;
                    c->prebuffering = true;
                    break;
                }
                continue;
            }

            bool slotPresent = (curSlot->ready && curSlot->seq == seq);
            if (!slotPresent) {
                // Isolated missing slot while audio exists ahead -> Pitch-Matched PLC!
                if (c->plcSynthSeq != seq) {
                    if ((int32_t)(hiSeq - seq) >= 1 && c->consecutivePlcFrames < 6) {
                        c->consecutivePlcFrames++;
                        synthesizePlcFrame(c, seq);
                    } else {
                        // Long consecutive blackout: fade out cleanly into full rebuffer
                        if (c->starveBoostMs < MAX_BUFFER_MS) {
                            c->starveBoostMs += 120.0f;
                            if (c->starveBoostMs > MAX_BUFFER_MS) c->starveBoostMs = MAX_BUFFER_MS;
                        }
                        if (c->fadeVolume > 0.005f) {
                            c->fadeVolume *= 0.97f;
                            c->lastOutL *= 0.985f;
                            c->lastOutR *= 0.985f;
                            outL[i] += c->lastOutL * c->fadeVolume;
                            outR[i] += c->lastOutR * c->fadeVolume;
                        } else {
                            c->fadeVolume = 0.0f;
                            c->lastOutL = 0.0f;
                            c->lastOutR = 0.0f;
                            c->prebuffering = true;
                            break;
                        }
                        continue;
                    }
                }
            } else {
                c->consecutivePlcFrames = 0;
            }

            if (c->fadeVolume < 1.0f) {
                c->fadeVolume += (1.0f / 128.0f);
                if (c->fadeVolume > 1.0f) c->fadeVolume = 1.0f;
            }

            float l0 = 0.0f, r0 = 0.0f, l1 = 0.0f, r1 = 0.0f;
            readStreamSample(c, curSampleIdx, &l0, &r0);
            readStreamSample(c, curSampleIdx + 1, &l1, &r1);
            float frac = (float)(c->readGlobalSample - (double)curSampleIdx);

            float smpL = (l0 + frac * (l1 - l0)) * c->fadeVolume;
            float smpR = (r0 + frac * (r1 - r0)) * c->fadeVolume;

            c->lastOutL = smpL;
            c->lastOutR = smpR;
            outL[i] += smpL;
            outR[i] += smpR;

            if (slotPresent) {
                uint32_t pw = c->plcWritePos & 1023;
                c->plcHistL[pw] = l0;
                c->plcHistR[pw] = r0;
                c->plcWritePos++;
            }

            c->readGlobalSample += step;
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

            HBRUSH bgBrush = CreateSolidBrush(RGB(18, 20, 24));
            FillRect(memDC, &rc, bgBrush);
            DeleteObject(bgBrush);

            RECT headerRc = { 0, 0, rc.right, 38 };
            HBRUSH headerBrush = CreateSolidBrush(RGB(14, 15, 18));
            FillRect(memDC, &headerRc, headerBrush);
            DeleteObject(headerBrush);

            SetBkMode(memDC, TRANSPARENT);

            HFONT hFontTitle = CreateFontA(18, 0, 0, 0, FW_BOLD, FALSE, FALSE, FALSE,
                                          DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                                          CLEARTYPE_QUALITY, DEFAULT_PITCH, "Segoe UI");
            HFONT oldFont = (HFONT)SelectObject(memDC, hFontTitle);
            SetTextColor(memDC, RGB(0, 220, 200));
            TextOutA(memDC, 16, 8, "WOB 5.0", 7);

            HFONT hFontSmall = CreateFontA(12, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE,
                                           DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                                           CLEARTYPE_QUALITY, DEFAULT_PITCH, "Segoe UI");
            SelectObject(memDC, hFontSmall);
            SetTextColor(memDC, RGB(130, 130, 140));
            char subTitle[128];
            snprintf(subTitle, sizeof(subTitle), "Ultra-Broadcast Iglesia  |  IP PC: %s", state->localIpStr);
            TextOutA(memDC, 88, 12, subTitle, (int)strlen(subTitle));

            int count = state->activeClientCount;
            HBRUSH dotBrush;
            char statusText[128];
            char infoText[128];
            char infoText2[128];

            if (count > 0) {
                dotBrush = CreateSolidBrush(RGB(46, 213, 115));
                ClientStream* first = NULL;
                for (int c = 0; c < MAX_CLIENTS; ++c) {
                    if (state->clients[c].active) { first = &state->clients[c]; break; }
                }

                if (first) {
                    const char* tierName = (first->currentTier == 2) ? "SUPERVIVENCIA (BFP6+K2)" :
                                           (first->currentTier == 1) ? "BLINDADO (BFP6+K3)" : "HD ESTUDIO (BFP8+K4)";
                    float effBuf = (first->starveBoostMs > first->netTargetBufferMs) ? first->starveBoostMs : first->netTargetBufferMs;
                    snprintf(statusText, sizeof(statusText), "CONECTADO: %s  [%s]", first->ipStr, tierName);
                    snprintf(infoText, sizeof(infoText), "Bufer: %.0f ms  |  Jitter: %.1f ms  |  Perdida: %.1f%%",
                             effBuf, first->currentJitterMs, first->lossPermille / 10.0f);
                    snprintf(infoText2, sizeof(infoText2), "Paquetes: %u  |  Curados FEC: %u  |  NACKs: %u",
                             (unsigned int)first->packetsReceived, (unsigned int)first->fecRecovered, (unsigned int)first->nackRecovered);
                } else {
                    snprintf(statusText, sizeof(statusText), "CONECTADO (En vivo)");
                    snprintf(infoText, sizeof(infoText), "Escudo Wi-Fi Activo");
                    snprintf(infoText2, sizeof(infoText2), "Sistema Anti-Cortes en ejecucion");
                }
                SetTextColor(memDC, RGB(46, 213, 115));
            } else {
                dotBrush = CreateSolidBrush(RGB(255, 160, 40));
                strcpy(statusText, "ESPERANDO CONEXION DESDE LA APP WOB...");
                strcpy(infoText, "Abre la app WOB en tu celular y pulsa CONECTAR.");
                strcpy(infoText2, "Proteccion FEC + NACK + Auto-Bufer lista para Wi-Fi congestionado.");
                SetTextColor(memDC, RGB(240, 180, 80));
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

            RECT boxRc = { 16, 76, rc.right - 16, 144 };
            HBRUSH boxBrush = CreateSolidBrush(RGB(26, 28, 33));
            FillRect(memDC, &boxRc, boxBrush);
            DeleteObject(boxBrush);

            SelectObject(memDC, hFontSmall);
            SetTextColor(memDC, RGB(180, 180, 190));
            TextOutA(memDC, 28, 82, "TELEMETRIA WI-FI EN VIVO:", 25);

            HFONT hFontSub = CreateFontA(13, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE,
                                        DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                                        CLEARTYPE_QUALITY, DEFAULT_PITCH, "Segoe UI");
            SelectObject(memDC, hFontSub);
            SetTextColor(memDC, RGB(120, 210, 190));
            TextOutA(memDC, 28, 100, infoText, (int)strlen(infoText));
            SetTextColor(memDC, RGB(150, 180, 210));
            TextOutA(memDC, 28, 120, infoText2, (int)strlen(infoText2));

            SelectObject(memDC, hFontSmall);
            SetTextColor(memDC, RGB(130, 130, 140));
            TextOutA(memDC, 20, 158, "L:", 2);
            TextOutA(memDC, 20, 178, "R:", 2);

            int meterWidth = rc.right - 80;
            int barL = (int)(state->meterPeakL * (float)meterWidth);
            int barR = (int)(state->meterPeakR * (float)meterWidth);
            if (barL > meterWidth) barL = meterWidth;
            if (barR > meterWidth) barR = meterWidth;

            HBRUSH mBg = CreateSolidBrush(RGB(32, 35, 42));
            RECT mBgL = { 42, 160, 42 + meterWidth, 170 };
            RECT mBgR = { 42, 180, 42 + meterWidth, 190 };
            FillRect(memDC, &mBgL, mBg);
            FillRect(memDC, &mBgR, mBg);
            DeleteObject(mBg);

            HBRUSH mBar = CreateSolidBrush(RGB(0, 210, 180));
            if (barL > 0) {
                RECT rL = { 42, 160, 42 + barL, 170 };
                FillRect(memDC, &rL, mBar);
            }
            if (barR > 0) {
                RECT rR = { 42, 180, 42 + barR, 190 };
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
            state->editorRect.bottom = 205;
            state->editorRect.right = 460;
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
                                                    WS_CHILD | WS_VISIBLE, 0, 0, 460, 205,
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
        case 45: if (ptr) { lstrcpyA((char*)ptr, "WOB 5.0"); return 1; } break;
        case 47: if (ptr) { lstrcpyA((char*)ptr, "WOB Audio"); return 1; } break;
        case 48: if (ptr) { lstrcpyA((char*)ptr, "WOB 5.0"); return 1; } break;
        case 49: return 5000;
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
    initQpc();
    memset(&g_state, 0, sizeof(g_state));
    memset(g_clientStorage, 0, sizeof(g_clientStorage));
    g_state.clients = g_clientStorage;
    g_state.hostSampleRate = 44100.0f;
    g_state.masterGain = 1.0f;

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
    g_effect.version = 5000;
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
