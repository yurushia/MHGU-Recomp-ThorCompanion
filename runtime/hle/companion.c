// The companion panel's reader: what a second screen shows beside the game (AYN Thor: android/java .../Companion.java),
// read from the game ten times a second on the game's thread and kept as a JSON text for whoever draws it.
//   {"v":1,"place":"quest","quest":101,"stage":1,"zone":4,"time":{"left":2987,"limit":3000},
//    "hunter":{"name":"..","weapon":7,"hp":100,"hpMax":150,"hpRed":110,"st":900.0,"stMax":900.0,"sharp":300,"sharpMax":450,
//              "x":0.0,"y":0.0,"z":0.0},
//    "monsters":[{"id":2,"name":"Rathalos","hp":1234,"hpMax":4000,"x":0.0,"y":0.0,"z":0.0}],
//    "items":[{"slot":0,"id":9,"n":10,"name":"Potion","sel":true}],"using":0}
// "using": the pouch slot of an item asked for with companion_use that the game hasn't taken yet (absent: none).
// place: "quest", "town" (then "town": its type, 0 Hub, 1-6 villages, 7 Hunters' Pub) or "menu" (no hunter).
//   MHGU_COMPANION_LOG=1   log the text once a second (to check the values on any platform)
//
// Where it comes from (main v1.4.0, runtime addresses; the hunter, quest and enemy list as in presence.c, hpscale.c):
// - The hunter (sPlayer *0x1a85b58 +0x1c: the 4 slots, ours sGameControl *0x1a8ca48 +0x30): +0x40 position (3 floats),
//   +0x1054 zone, +0x13d6 HP, +0x13d8 its maximum, +0x13da the red part (s16 each), +0x13e0 stamina, +0x13e4 its
//   maximum (floats), +0x2774 sharpness, +0x2778 the most it can be now (s32), +0x3298 the selected pouch slot.
// - A monster (sEnemy *0x1a51fe0 +0x1c: the 10 live ones): status block *(monster + 0x1428): +0x40 position, +0x1b6
//   network id (below 10000: large), +0x370 HP, +0x374 its maximum (s32: 0x29a764 keeps HP between 0 and the maximum,
//   0x2c04a0 sets the maximum). monster +0xb5f4: its number (em<nnn>), named through the quest's list of large monsters.
// - The quest's time (sQuest *0x1a8ad90): +0x1c updates passed (s32), +0xcc the limit in minutes; the game shows
//   (limit * 60 * rate - passed) / rate seconds, rate = the float at *0x2326764 +0x38 (0x269b04 and others).
// - The pouch (*0x1a584b8): from +0x1a, 4 bytes a slot (0..0x31): u16 item, s16 count. The item bar's order is the
//   game's own walk (0x4880fc: player, slot, 0 right), from the empty place 0x28 until it comes round.
//
// Using an item (companion_use, a touch on the panel): as the RadialMenu Forge plugin does it. On the game's thread, once
// the hunter may pick an item (the item bar handler's own checks, itembar.c: 0x47bcd0 == 1, 0x47b918 == 0, vfunc +0x230
// (player, 1) == 1), the slot is written to +0x3298 and Y is held on the pad for a moment (companion_buttons, added to
// what GetNpadState gives). The game took it when the hunter turns busy or the slot's count drops; else Y is pressed
// again, a few times. Given up when the hunter isn't free within a few seconds, or on leaving the quest.
// Not known yet: with the weapon out Y may only sheathe it (the hunter turns busy, which counts as taken here), and with
// [Actions] Enable=1 the pad's Y reaches the game through the action layer, which may not pass it on.
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

#include "../hle.h"
#include "../net/discord.h"
#include "companion.h"
#include "companion_items.h"

#define G_GAMECONTROL  0x1a8ca48u
#define G_SPLAYER      0x1a85b58u
#define G_SQUEST       0x1a8ad90u
#define G_SENEMY       0x1a51fe0u
#define G_POUCH        0x1a584b8u
#define G_RATE         0x2326764u  // *: +0x38 the update rate the quest's time is counted in (float)
#define GC_TOWN        0x4cu
#define GC_SLOT        0x30u
#define TOWN_NONE      0xffu
#define PL_POS         0x40u
#define PL_ZONE        0x1054u
#define PL_DATA        0x13c4u
#define PD_NAME        0x514u
#define PD_WEAPON      0x14cu
#define PL_HP          0x13d6u
#define PL_HP_MAX      0x13d8u
#define PL_HP_RED      0x13dau
#define PL_STAMINA     0x13e0u
#define PL_STAMINA_MAX 0x13e4u
#define PL_SHARP       0x2774u
#define PL_SHARP_MAX   0x2778u
#define PL_ITEM_SLOT   0x3298u
#define NO_ITEM_SLOT   0x28u
#define POUCH_SLOTS    0x32u
#define FN_ITEM_NEXT   0x4880fcu
#define FN_PL_IS_LOCAL 0x47bcd0u    // (player) 1: ours
#define FN_PL_BUSY     0x47b918u    // (player) 0: free
#define VF_CAN_SELECT  0x230u      // vfunc (player, 1) 1: may pick an item now
#define FN_PROWLER_BAR 0x1417d68u
#define VF_PROWLER_BAR 0x5c4u      // holds FN_PROWLER_BAR in uOtomo's vtable only (itembar.c)
#define EN_LIST        0x1cu
#define EN_COUNT       10
#define EN_INFO        0x1428u
#define EN_NUMBER      0xb5f4u
#define INFO_POS       0x40u
#define INFO_ID        0x1b6u
#define INFO_HP        0x370u
#define INFO_HP_MAX    0x374u
#define SQ_PASSED      0x1cu
#define SQ_LIMIT       0xccu
#define SQ_DATA        0xa4u
#define QD_NUMBER      0x8u
#define QD_STAGE       0x10u
#define QD_MONSTERS    0x64u       // 5 of 14 bytes, u16 monster id first
#define TICK_NS        100000000ull
#define MAX_ITEMS      24
#define USE_WAIT_NS    3000000000ull  // how long a use waits for the hunter to be free
#define USE_HOLD_NS    100000000ull   // Y held
#define USE_TAKEN_NS   400000000ull   // how long the game has to take a press before the next one
#define USE_TRIES      4
#define BTN_Y_BIT      (1ull << 3)    // NpadButton Y (input.h)

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static char g_json[8192];
static size_t g_len;

// A use asked for: g_use_slot by any thread (-1: none), the rest by the game's thread only.
static int g_use_slot = -1;
static int u_slot = -1, u_phase, u_tries, u_count;
static uint64_t u_deadline, u_hold_until;

static bool sane(uint32_t a) { return a >= 0x08000000u && a < 0xF0000000u && !(a & 3); }

typedef struct { char *p, *end; } Out;

static void put(Out *o, const char *fmt, ...) {
    if (o->p >= o->end) return;
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(o->p, (size_t)(o->end - o->p), fmt, ap);
    va_end(ap);
    if (n < 0 || n >= o->end - o->p) o->p = o->end;  // (full: the text is dropped, see publish)
    else o->p += n;
}

// A JSON string: quotes, backslashes and control characters escaped; the rest (UTF-8) as it is.
static void put_str(Out *o, const char *s) {
    put(o, "\"");
    for (; s && *s; s++) {
        unsigned char ch = (unsigned char)*s;
        if (ch == '"' || ch == '\\') put(o, "\\%c", ch);
        else if (ch < 0x20) put(o, "\\u%04x", ch);
        else put(o, "%c", ch);
    }
    put(o, "\"");
}

static float finite_or_0(float f) { return f == f && f > -1e9f && f < 1e9f ? f : 0.0f; }

static const char *item_name(uint32_t id) {
    int lo = 0, hi = (int)(sizeof g_item_names / sizeof *g_item_names) - 1;
    while (lo <= hi) {
        int mid = (lo + hi) / 2;
        if (g_item_names[mid].id == id) return g_item_names[mid].name;
        if (g_item_names[mid].id < id) lo = mid + 1;
        else hi = mid - 1;
    }
    return "";
}

static uint32_t call(Cpu *c, uint32_t fn, uint32_t r0, uint32_t r1, uint32_t r2) {
    uint32_t saved[16];
    for (int i = 0; i < 16; i++) saved[i] = c->r[i];
    c->r[0] = r0, c->r[1] = r1, c->r[2] = r2;
    guest_call(c, fn);
    uint32_t v = c->r[0];
    for (int i = 0; i < 16; i++) c->r[i] = saved[i];
    return v;
}

static uint32_t local_player(void) {
    uint32_t gc = LD32(G_GAMECONTROL), sp = LD32(G_SPLAYER);
    if (!sane(gc) || !sane(sp) || LD8(gc + GC_TOWN) != TOWN_NONE) return 0;  // (in a quest only)
    uint32_t slot = LD32(gc + GC_SLOT), pl = slot < 4 ? LD32(sp + 0x1c + 4 * slot) : 0, vt = sane(pl) ? LD32(pl) : 0;
    return vt && LD32(vt + VF_PROWLER_BAR) != FN_PROWLER_BAR ? pl : 0;  // (not a Prowler: its bar is another one)
}

static int pouch_count(int slot) {
    uint32_t pouch = LD32(G_POUCH);
    return sane(pouch) ? (int16_t)LD16(pouch + 0x1c + 4 * (uint32_t)slot) : 0;
}

void companion_use(int slot) {
    if (slot < 0 || slot >= (int)POUCH_SLOTS || slot == (int)NO_ITEM_SLOT) return;
    pthread_mutex_lock(&g_lock);
    g_use_slot = slot;
    pthread_mutex_unlock(&g_lock);
}

// Each time the game reads player 1's pad: steps a use that is waiting; returns the buttons to add to the pad (Y).
uint64_t companion_buttons(Cpu *c) {
    uint64_t now = plat_now_ns();
    pthread_mutex_lock(&g_lock);
    int asked = g_use_slot;
    g_use_slot = -1;
    pthread_mutex_unlock(&g_lock);
    if (asked >= 0) u_slot = asked, u_phase = 0, u_tries = 0, u_deadline = now + USE_WAIT_NS, u_hold_until = 0;
    if (u_slot < 0) return 0;
    uint32_t pl = local_player();
    if (!pl || pouch_count(u_slot) <= 0) { u_slot = -1; return 0; }  // (left the quest; nothing in that slot)
    if (u_phase == 0) {
        uint32_t can = LD32(LD32(pl) + VF_CAN_SELECT);
        if (call(c, FN_PL_IS_LOCAL, pl, 0, 0) == 1 && call(c, FN_PL_BUSY, pl, 0, 0) == 0 && can && call(c, can, pl, 1, 0) == 1) {
            ST16(pl + PL_ITEM_SLOT, u_slot);
            u_count = pouch_count(u_slot);
            u_phase = 1, u_tries++, u_hold_until = now + USE_HOLD_NS, u_deadline = now + USE_TAKEN_NS;
        } else if (now >= u_deadline) u_slot = -1;  // (never free)
    } else if (now >= u_hold_until) {
        if (call(c, FN_PL_BUSY, pl, 0, 0) != 0 || pouch_count(u_slot) < u_count) u_slot = -1;  // (the game took it)
        else if (now >= u_deadline) {
            if (u_tries >= USE_TRIES) u_slot = -1;
            else u_phase = 0, u_deadline = now + USE_WAIT_NS;
        }
    }
    return u_slot >= 0 && u_phase == 1 && now < u_hold_until ? BTN_Y_BIT : 0;
}

static void put_hunter(Out *o, uint32_t pl, uint32_t pd, bool quest) {
    char name[33];
    memcpy(name, G2H(pd + PD_NAME), 32), name[32] = 0;
    put(o, ",\"hunter\":{\"name\":");
    put_str(o, name);
    put(o, ",\"weapon\":%u", LD32(pd + PD_WEAPON));
    if (quest)
        put(o, ",\"hp\":%d,\"hpMax\":%d,\"hpRed\":%d,\"st\":%.1f,\"stMax\":%.1f,\"sharp\":%d,\"sharpMax\":%d",
            (int16_t)LD16(pl + PL_HP), (int16_t)LD16(pl + PL_HP_MAX), (int16_t)LD16(pl + PL_HP_RED),
            (double)finite_or_0(LDF(pl + PL_STAMINA)), (double)finite_or_0(LDF(pl + PL_STAMINA_MAX)),
            (int32_t)LD32(pl + PL_SHARP), (int32_t)LD32(pl + PL_SHARP_MAX));
    put(o, ",\"x\":%.1f,\"y\":%.1f,\"z\":%.1f}", (double)finite_or_0(LDF(pl + PL_POS)),
        (double)finite_or_0(LDF(pl + PL_POS + 4)), (double)finite_or_0(LDF(pl + PL_POS + 8)));
}

// The quest's time left, in seconds, as the game works it out.
static void put_time(Out *o, uint32_t sq) {
    uint32_t rp = LD32(G_RATE);
    int rate = sane(rp) ? (int)finite_or_0(LDF(rp + 0x38)) : 0;
    int32_t limit = (int32_t)LD32(sq + SQ_LIMIT), passed = (int32_t)LD32(sq + SQ_PASSED);
    if (rate < 1 || rate > 1000 || limit < 1 || limit > 1000 || passed < 0) return;
    int64_t left = ((int64_t)limit * 60 * rate - passed) / rate;
    put(o, ",\"time\":{\"left\":%d,\"limit\":%d}", (int)(left < 0 ? 0 : left), limit * 60);
}

static void put_monsters(Out *o, uint32_t qd) {
    put(o, ",\"monsters\":[");
    uint32_t se = LD32(G_SENEMY);
    int n = 0;
    for (uint32_t k = 0; sane(se) && k < EN_COUNT; k++) {
        uint32_t en = LD32(se + EN_LIST + 4 * k), info = sane(en) ? LD32(en + EN_INFO) : 0;
        if (!sane(info)) continue;
        uint32_t net = LD16(info + INFO_ID);
        int32_t hp = (int32_t)LD32(info + INFO_HP), max = (int32_t)LD32(info + INFO_HP_MAX);
        if (net >= 10000 || max <= 0 || max >= 10000000) continue;  // (small monsters; not set up yet)
        // Its id as the quest lists it (with the variant: a deviant has the base species' number), else the number alone
        uint32_t number = LD32(en + EN_NUMBER) & 0xff, id = number;
        for (uint32_t i = 0; i < 5; i++) {
            uint32_t q = LD16(qd + QD_MONSTERS + 14 * i);
            if (q && q < 0x1000 && (q & 0xff) == number) { id = q; break; }
        }
        const char *name = id ? presence_monster_name((uint16_t)id) : NULL;
        put(o, "%s{\"id\":%u,\"name\":", n++ ? "," : "", id);
        put_str(o, name ? name : "");
        put(o, ",\"hp\":%d,\"hpMax\":%d,\"x\":%.1f,\"y\":%.1f,\"z\":%.1f}", hp < 0 ? 0 : hp > max ? max : hp, max,
            (double)finite_or_0(LDF(info + INFO_POS)), (double)finite_or_0(LDF(info + INFO_POS + 4)),
            (double)finite_or_0(LDF(info + INFO_POS + 8)));
    }
    put(o, "]");
}

// The item bar, in its own order. (A Prowler's bar is another one, with no pouch: none listed.)
static void put_items(Out *o, Cpu *c, uint32_t pl) {
    put(o, ",\"items\":[");
    uint32_t pouch = LD32(G_POUCH), vt = LD32(pl);
    if (sane(pouch) && vt && LD32(vt + VF_PROWLER_BAR) != FN_PROWLER_BAR) {
        uint32_t selected = LD16(pl + PL_ITEM_SLOT), seen[MAX_ITEMS], s = NO_ITEM_SLOT;
        int n = 0;
        for (uint32_t k = 0; k < POUCH_SLOTS && n < MAX_ITEMS; k++) {
            uint32_t next = call(c, FN_ITEM_NEXT, pl, s, 0) & 0xffff;
            if (next == s || next == NO_ITEM_SLOT || next >= POUCH_SLOTS) break;
            bool dup = false;
            for (int i = 0; i < n; i++) dup |= seen[i] == next;
            if (dup) break;
            s = seen[n] = next;
            uint32_t id = LD16(pouch + 0x1a + 4 * next);
            put(o, "%s{\"slot\":%u,\"id\":%u,\"n\":%d,\"name\":", n++ ? "," : "", next, id,
                (int16_t)LD16(pouch + 0x1c + 4 * next));
            put_str(o, item_name(id));
            put(o, ",\"sel\":%s}", next == selected ? "true" : "false");
        }
    }
    put(o, "]");
}

void companion_tick(Cpu *c) {
    static uint64_t next, logged;
    uint64_t now = plat_now_ns();
    if (now < next) return;
    next = now + TICK_NS;

    char text[sizeof g_json];
    Out o = {text, text + sizeof text};
    uint32_t gc = LD32(G_GAMECONTROL), sp = LD32(G_SPLAYER);
    uint32_t slot = sane(gc) ? LD32(gc + GC_SLOT) : 0;
    uint32_t pl = sane(sp) && slot < 4 ? LD32(sp + 0x1c + 4 * slot) : 0, pd = sane(pl) ? LD32(pl + PL_DATA) : 0;
    uint32_t sq = LD32(G_SQUEST), qd = sane(sq) ? LD32(sq + SQ_DATA) : 0;
    uint32_t town = sane(gc) ? LD8(gc + GC_TOWN) : 0;
    uint32_t quest = town == TOWN_NONE && sane(qd) ? LD32(qd + QD_NUMBER) : 0;
    put(&o, "{\"v\":1");
    if (!sane(pd) || (town == TOWN_NONE && !quest)) put(&o, ",\"place\":\"menu\"");
    else if (quest) {
        put(&o, ",\"place\":\"quest\",\"quest\":%u,\"stage\":%u,\"zone\":%u", quest, LD8(qd + QD_STAGE), LD8(pl + PL_ZONE));
        put_time(&o, sq);
        put_hunter(&o, pl, pd, true);
        put_monsters(&o, qd);
        put_items(&o, c, pl);
        if (u_slot >= 0) put(&o, ",\"using\":%d", u_slot);
    } else {
        put(&o, ",\"place\":\"town\",\"town\":%u", town);
        put_hunter(&o, pl, pd, false);
    }
    put(&o, "}");
    if (o.p >= o.end) return;  // (didn't fit: the last one stays)

    pthread_mutex_lock(&g_lock);
    g_len = (size_t)(o.p - text);
    memcpy(g_json, text, g_len + 1);
    pthread_mutex_unlock(&g_lock);
    if (ENV("MHGU_COMPANION_LOG") && now - logged >= 1000000000ull) logged = now, LOG("[companion] %s\n", text);
}

size_t companion_snapshot(char *buf, size_t size) {
    pthread_mutex_lock(&g_lock);
    size_t n = g_len < size ? g_len : 0;
    if (n) memcpy(buf, g_json, n + 1);
    pthread_mutex_unlock(&g_lock);
    return n;
}

#ifdef __ANDROID__
#include <jni.h>

// Companion.snapshot(): the JSON as UTF-8 bytes (not a jstring: a hunter's name may hold what Java's modified UTF-8
// doesn't take), null when there is none yet.
JNIEXPORT jbyteArray JNICALL Java_io_github_morerokk_mhgurecomp_Companion_snapshot(JNIEnv *env, jclass cls) {
    (void)cls;
    char text[sizeof g_json];
    size_t n = companion_snapshot(text, sizeof text);
    if (!n) return NULL;
    jbyteArray a = (*env)->NewByteArray(env, (jsize)n);
    if (a) (*env)->SetByteArrayRegion(env, a, 0, (jsize)n, (const jbyte *)text);
    return a;
}

// Companion.use(slot): a touch on an item of the panel.
JNIEXPORT void JNICALL Java_io_github_morerokk_mhgurecomp_Companion_use(JNIEnv *env, jclass cls, jint slot) {
    (void)env, (void)cls;
    companion_use((int)slot);
}
#endif
