// The companion panel's reader: what a second screen shows beside the game (AYN Thor: android/java .../Companion.java),
// read from the game ten times a second on the game's thread and kept as a JSON text for whoever draws it.
//   {"v":1,"place":"quest","quest":101,"stage":1,"zone":4,"time":{"left":2987,"limit":3000},
//    "hunter":{"name":"..","weapon":7,"hp":100,"hpMax":150,"hpRed":110,"st":900.0,"stMax":900.0,"sharp":300,"sharpMax":450,
//              "x":0.0,"y":0.0,"z":0.0},
//    "monsters":[{"id":2,"name":"Rathalos","zone":4,"hp":1234,"hpMax":4000,"x":0.0,"y":0.0,"z":0.0}],
//    "items":[{"slot":0,"id":9,"n":10,"name":"Potion","sel":true}],"pinMax":8,"pins":[{"id":9,"name":"Potion"}],
//    "using":0,"step":"sheathe","waitFor":"hunter","result":{"slot":0,"ok":false,"why":"blocked"}}
// A position (the hunter's, a monster's "x","y","z") is there only when the game has a real one: never a made-up 0, 0, 0.
// "using": the pouch slot of an item asked for with companion_use that the game hasn't taken yet (absent: none);
// "step": where that has got to: "wait" (for the hunter to be free), "sheathe" (putting the weapon away), "use". "waitFor": what a
// "wait" is waiting for: "hunter" (idle, the weapon away) or "bar" (the game's own "may use an item" test). "result": how the last
// use ended, for a moment: ok (the game took it), or why not: "blocked" (the game's own test said no for three seconds),
// "ignored" (the button was pressed a few times and not taken), "sheathe" (the weapon would not go away), "empty" (none left),
// "timeout" (ten seconds). "pins": the pinned items, in their order (Settings > Duo Companion), "pinMax" what a page holds.
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
// - The quest's time (sQuest *0x1a8ad90): +0x1c the updates LEFT (s32, counts down; a first version took it for the updates
//   passed and showed the time going up), +0xcc the limit in minutes. The game's own code works out the time passed as
//   (limit * 60 * rate - left) / rate seconds (0x269b04 and others), so the time left is left / rate, with rate = the
//   float at *0x2326764 +0x38. (The "Inf. Quest Time" cheat holds +0x1c high: it only keeps the quest alive if it is the
//   time left.)
// - The pouch (*0x1a584b8): from +0x1a, 4 bytes a slot (0..0x31): u16 item, s16 count. The item bar's order is the
//   game's own walk (0x4880fc: player, slot, 0 right), from the empty place 0x28 until it comes round.
//
// Using an item (companion_use, a touch on the panel), in one touch, as the hunter's weapon needs:
//   sheathed, or a Sword & Shield out (the one weapon that uses items drawn)   select the slot, press Y
//   any other weapon out                                                       press Y to put it away, wait until it is
//                                                                              away and the hunter idle, then as above
// All on the game's thread (companion_buttons, from GetNpadState: its result is added to the pad's buttons, and Y is held
// for a moment, which is how the game takes a press).
// - The hunter's state: the weapon is out when the u8 at player +0x114c is 1; its action is the pair u16 +0x2506 / +0x250a
//   (hle/input_view.c: idle is 1:0, a roll or an attack another pair). Y puts a drawn weapon away only when the hunter is
//   idle (docs/GAME-ACTIONS.md), so Y waits for idle (but not for ever: three seconds after the touch it is pressed anyway).
// - "May use an item" is the item bar handler's own test (itembar.c): 0x47bcd0 == 1, 0x47b918 == 0 (that one is bit 3 of the
//   flags byte at +0x13cc, not a busy state, whatever itembar.c calls it) and vfunc +0x230 (player, 1) == 1. It does not look
//   at the weapon, so with the weapon out it passes and a Y only sheathes.
// - The game took a Y when the hunter's action is no longer the one it had at the press (or, for an item, the slot's count
//   dropped: a consumable's does, a tool's doesn't, and some drop late). Then nothing is pressed again for that step: a
//   second Y would use another item or the same one twice. Only when the hunter did nothing at all is Y pressed again
//   (a few times). The weapon is away when the +0x114c byte is 0.
// Given up after a few seconds, or on leaving the quest. Not known yet: with [Actions] Enable=1 the pad's Y reaches the game
// through the action layer, which may not pass it on; and the byte at +0x114c is checked on a Hammer only (input_view.c).
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
#define PL_DRAWN       0x114cu     // u8: 1 the weapon is out, 0 put away
#define PL_ACT_ST      0x2506u     // u16, with PL_ACT_NO: the hunter's current action; 1:0 is idle (input_view.c)
#define PL_ACT_NO      0x250au
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
#define FN_PL_FLAG3    0x47b918u    // (player) bit 3 of the flags byte at +0x13cc: 0 for the item bar to work (itembar.c: "busy")
#define VF_CAN_SELECT  0x230u      // vfunc (player, 1) 1: may pick an item now
#define FN_PROWLER_BAR 0x1417d68u
#define VF_PROWLER_BAR 0x5c4u      // holds FN_PROWLER_BAR in uOtomo's vtable only (itembar.c)
#define EN_LIST        0x1cu
#define EN_COUNT       10
#define EN_INFO        0x1428u
#define EN_NUMBER      0xb5f4u
#define EN_AREA        0x1054u     // monster: its zone, u8 (as PL_ZONE; emsync.c reads the same byte)
#define INFO_POS       0x40u
#define INFO_ID        0x1b6u
#define INFO_HP        0x370u
#define INFO_HP_MAX    0x374u
#define SQ_TICKS_LEFT  0x1cu
#define SQ_LIMIT       0xccu
#define SQ_DATA        0xa4u
#define QD_NUMBER      0x8u
#define QD_STAGE       0x10u
#define QD_MONSTERS    0x64u       // 5 of 14 bytes, u16 monster id first
#define TICK_NS        100000000ull
#define MAX_ITEMS      50          // the pouch's slots: a page of the panel shows eight
#define WEAPON_SNS     1u          // the weapon type (as g_weapons in presence.c) that uses items with the weapon out
#define ACT_IDLE       ((1u << 16) | 0u)
#define USE_TOTAL_NS   10000000000ull  // the whole sequence, from the touch
#define USE_HOLD_NS    100000000ull    // Y held
#define USE_TAKEN_NS   600000000ull    // how long the game has to show it took a press that was meant to use the item
#define SHEATHE_WAIT_NS 1200000000ull  // ... that was meant to put the weapon away
#define IDLE_GRACE_NS  3000000000ull   // how long a press waits for the hunter to be idle (a Y in the middle of a move is lost)
#define SETTLE_NS      150000000ull    // idle again after the weapon is away: a moment to be sure
#define AWAY_FORCE_NS  1000000000ull   // ... or this long after it is away, idle or not
#define USE_TRIES      4
#define BLOCK_NS       3000000000ull   // how long the game's own "may use an item" test may say no (with the hunter free) before the use is given up
#define RESULT_OK_NS   1500000000ull   // how long the panel is told a use went through
#define RESULT_BAD_NS  8000000000ull   // ... and why one did not (long enough to read it and tap again)
#define PIN_MAX        8               // pinned items: what the first page of the panel holds
#define SHEATHE_TRIES  3
#define BTN_Y_BIT      (1ull << 3)     // NpadButton Y (input.h)

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static char g_json[16384];
static size_t g_len;

// A use asked for: g_use_slot by any thread (-1: none), the rest by the game's thread only.
static int g_use_slot = -1;
enum { U_WAIT, U_SHEATHE, U_USE };
static volatile int u_slot = -1;  // (the panel's text is made on the same thread: volatile for the tests' sake)
static volatile int u_phase;
static int u_tries, u_sheathes, u_count;
static bool u_acted, u_after_sheathe;
static uint32_t u_act_press;
static uint64_t u_deadline, u_hold_until, u_retry, u_wait_from, u_away_at, u_idle_since, u_blocked_since;
static volatile int u_wait_for;  // while waiting: 0 for the hunter (idle, the weapon away), 1 for the game's own "may use an item" test
// How the last use ended, for the panel (in the JSON a while): slot -1 none. why: "taken", "blocked", "ignored", "sheathe", "empty", "timeout".
static struct { int slot, ok; const char *why; uint64_t until; } u_res = {-1, 0, "", 0};

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
// A position that is really one: finite and sane (never made up: a panel that gets none says so), and, for a monster, not
// the all-zero one of an object the game has not set up yet.
static bool pos_ok(float x, float y, float z, bool zero_ok) {
    bool fin = x == x && y == y && z == z && x > -1e7f && x < 1e7f && y > -1e7f && y < 1e7f && z > -1e7f && z < 1e7f;
    return fin && (zero_ok || x != 0.0f || y != 0.0f || z != 0.0f);
}

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

// The pinned items (Settings > Duo Companion; config.c's PinnedItems, item numbers in a comma separated list): read again when
// the text changes. They are in the JSON by number and name; the panel puts them first on its first page.
const char *config_companion_pins(void);  // (config.c; the tests make one)
static uint16_t g_pins[PIN_MAX];
static int g_npins;
static char g_pins_text[96];

static void refresh_pins(void) {
    const char *t = config_companion_pins();
    char now[sizeof g_pins_text];
    snprintf(now, sizeof now, "%s", t ? t : "");
    if (!strcmp(now, g_pins_text)) return;
    memcpy(g_pins_text, now, sizeof now);
    g_npins = 0;
    for (const char *q = now; *q && g_npins < PIN_MAX;) {
        while (*q == ',' || *q == ' ') q++;
        unsigned v = 0, digits = 0;
        while (*q >= '0' && *q <= '9' && digits < 6) v = v * 10 + (unsigned)(*q++ - '0'), digits++;
        while (*q && *q != ',') q++;  // (anything else up to the comma is ignored)
        bool dup = false;
        for (int i = 0; i < g_npins; i++) dup |= g_pins[i] == v;
        if (digits && v > 0 && v < 0x10000 && !dup) g_pins[g_npins++] = (uint16_t)v;
    }
}

static void put_pins(Out *o) {
    put(o, ",\"pinMax\":%d,\"pins\":[", PIN_MAX);
    for (int i = 0; i < g_npins; i++) {
        put(o, "%s{\"id\":%u,\"name\":", i ? "," : "", g_pins[i]);
        put_str(o, item_name(g_pins[i]));
        put(o, "}");
    }
    put(o, "]");
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

static bool use_log(void) { return ENV("MHGU_COMPANION_LOG") != NULL; }
#define ULOG(...) do { if (use_log()) LOG("[companion] use: " __VA_ARGS__); } while (0)

static void use_stop(const char *why) {
    ULOG("slot %d: %s\n", u_slot, why);
    u_slot = -1;
}

// A use that is over, and how: told to the panel for a while (a failure so the player reads why and can tap again)
static void use_end(bool ok, const char *why, const char *code) {
    ULOG("slot %d: %s\n", u_slot, why);
    u_res.slot = u_slot, u_res.ok = ok, u_res.why = code;
    u_res.until = plat_now_ns() + (ok ? RESULT_OK_NS : RESULT_BAD_NS);
    u_slot = -1;
}

static uint32_t hunter_act(uint32_t pl) { return ((uint32_t)LD16(pl + PL_ACT_ST) << 16) | LD16(pl + PL_ACT_NO); }

// Each time the game reads player 1's pad: steps a use that is waiting; returns the buttons to add to the pad (Y).
uint64_t companion_buttons(Cpu *c) {
    uint64_t now = plat_now_ns();
    pthread_mutex_lock(&g_lock);
    int asked = g_use_slot;
    g_use_slot = -1;
    pthread_mutex_unlock(&g_lock);
    if (asked >= 0) {
        u_slot = asked, u_phase = U_WAIT, u_tries = u_sheathes = 0, u_acted = u_after_sheathe = false;
        u_deadline = now + USE_TOTAL_NS, u_wait_from = now;
        u_hold_until = u_retry = u_away_at = u_idle_since = u_blocked_since = 0;
        u_wait_for = 0, u_res.slot = -1;
        ULOG("slot %d asked\n", asked);
    }
    if (u_slot < 0) return 0;
    uint32_t pl = local_player();
    if (!pl) return use_stop("left the quest"), 0;
    if (now >= u_deadline) return use_end(false, "gave up", "timeout"), 0;
    if (call(c, FN_PL_IS_LOCAL, pl, 0, 0) != 1) return use_stop("not our hunter"), 0;
    uint32_t pd = LD32(pl + PL_DATA), act = hunter_act(pl);
    bool drawn = LD8(pl + PL_DRAWN) != 0, sns = sane(pd) && LD32(pd + PD_WEAPON) == WEAPON_SNS;
    bool idle = act == ACT_IDLE, holding = now < u_hold_until;
    if (idle) {
        if (!u_idle_since) u_idle_since = now;
    } else u_idle_since = 0;
    if (u_phase != U_WAIT && act != u_act_press) u_acted = true;  // (the hunter did something since the press)

    if (u_phase == U_USE) {
        if (pouch_count(u_slot) < u_count) return use_end(true, "taken: its count dropped", "taken"), 0;
        if (u_acted) {
            if (!holding) return use_end(true, "taken: the hunter acted", "taken"), 0;
        } else if (!holding && now >= u_retry) {
            if (u_tries >= USE_TRIES) return use_end(false, "not taken", "ignored"), 0;
            u_phase = U_WAIT, u_wait_from = now;
        }
    } else if (u_phase == U_SHEATHE) {
        if (!drawn) {
            u_phase = U_WAIT, u_after_sheathe = true, u_away_at = now, u_wait_from = now;
            ULOG("slot %d: weapon away\n", u_slot);
        } else if (!holding && !u_acted && now >= u_retry) {  // (nothing happened: another Y; if it did, wait for it)
            if (u_sheathes >= SHEATHE_TRIES) return use_end(false, "the weapon would not go away", "sheathe"), 0;
            u_phase = U_WAIT, u_wait_from = now;
        }
    }
    if (u_phase == U_WAIT) {
        if (pouch_count(u_slot) <= 0) return use_end(false, "none left", "empty"), 0;
        bool free_now = idle || now - u_wait_from >= IDLE_GRACE_NS;
        if (drawn && !sns) {  // the weapon first: Y puts it away when the hunter is idle
            if (free_now) {
                u_phase = U_SHEATHE, u_sheathes++, u_acted = false, u_act_press = act;
                u_hold_until = now + USE_HOLD_NS, u_retry = now + SHEATHE_WAIT_NS;
                ULOG("slot %d: Y to put the weapon away (%d)\n", u_slot, u_sheathes);
            }
        } else {
            bool settled = !u_after_sheathe || (u_idle_since && now - u_idle_since >= SETTLE_NS) || now - u_away_at >= AWAY_FORCE_NS;
            bool ready = settled && (u_after_sheathe || free_now);
            u_wait_for = ready ? 1 : 0;
            if (ready) {
                // The game's own "may use an item" test. (The vtable is in main's image, below the heap: it is not sane(), and
                // asking for that made this test fail for ever: the use sat in "waiting" until it was given up.)
                uint32_t vt = LD32(pl), can = vt ? LD32(vt + VF_CAN_SELECT) : 0;
                if (call(c, FN_PL_FLAG3, pl, 0, 0) == 0 && can && call(c, can, pl, 1, 0) == 1) {
                    u_blocked_since = 0;
                    ST16(pl + PL_ITEM_SLOT, u_slot);
                    u_count = pouch_count(u_slot);
                    u_phase = U_USE, u_tries++, u_acted = false, u_act_press = act;
                    u_hold_until = now + USE_HOLD_NS, u_retry = now + USE_TAKEN_NS;
                    ULOG("slot %d: Y to use it (%d)%s\n", u_slot, u_tries, drawn ? ", weapon out" : "");
                } else if (!u_blocked_since) {
                    u_blocked_since = now;
                    ULOG("slot %d: the game says an item can't be used now\n", u_slot);
                } else if (now - u_blocked_since >= BLOCK_NS) {
                    return use_end(false, "the game's own test says no", "blocked"), 0;
                }
            }
        }
    }
    return u_slot >= 0 && u_phase != U_WAIT && now < u_hold_until ? BTN_Y_BIT : 0;
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
    float x = LDF(pl + PL_POS), y = LDF(pl + PL_POS + 4), z = LDF(pl + PL_POS + 8);
    if (pos_ok(x, y, z, true)) put(o, ",\"x\":%.1f,\"y\":%.1f,\"z\":%.1f", (double)x, (double)y, (double)z);  // (else none: not 0, 0, 0)
    put(o, "}");
}

// The quest's time left, in seconds: the updates left (counting down) over the rate the game counts them at.
static void put_time(Out *o, uint32_t sq) {
    uint32_t rp = LD32(G_RATE);
    int rate = sane(rp) ? (int)finite_or_0(LDF(rp + 0x38)) : 0;
    int32_t limit = (int32_t)LD32(sq + SQ_LIMIT), ticks = (int32_t)LD32(sq + SQ_TICKS_LEFT);
    if (rate < 1 || rate > 1000 || limit < 1 || limit > 1000) return;
    int64_t left = ticks / rate, total = (int64_t)limit * 60;
    put(o, ",\"time\":{\"left\":%d,\"limit\":%d}", (int)(left < 0 ? 0 : left > total ? total : left), (int)total);
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
        put(o, ",\"zone\":%u,\"hp\":%d,\"hpMax\":%d", LD8(en + EN_AREA), hp < 0 ? 0 : hp > max ? max : hp, max);
        float x = LDF(info + INFO_POS), y = LDF(info + INFO_POS + 4), z = LDF(info + INFO_POS + 8);
        // (no position, not a made-up one, when the game has none that is real: the panel then says "no position")
        if (pos_ok(x, y, z, false)) put(o, ",\"x\":%.1f,\"y\":%.1f,\"z\":%.1f", (double)x, (double)y, (double)z);
        put(o, "}");
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
        refresh_pins();
        put_pins(&o);
        if (u_slot >= 0) {
            const char *step = u_phase == U_SHEATHE || (u_phase == U_WAIT && u_after_sheathe) ? "sheathe" : u_phase == U_USE ? "use" : "wait";
            put(&o, ",\"using\":%d,\"step\":\"%s\"", u_slot, step);  // (waiting for the hunter after the weapon is away is still sheathing)
            if (u_phase == U_WAIT && !u_after_sheathe) put(&o, ",\"waitFor\":\"%s\"", u_wait_for ? "bar" : "hunter");
        }
        if (u_res.slot >= 0 && now < u_res.until)
            put(&o, ",\"result\":{\"slot\":%d,\"ok\":%s,\"why\":\"%s\"}", u_res.slot, u_res.ok ? "true" : "false", u_res.why);
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
