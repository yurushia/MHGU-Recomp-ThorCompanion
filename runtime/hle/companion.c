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
// Using an item (companion_use, a touch on the panel), in one touch. Modelled on the RadialMenu Forge plugin, the one way of
// using an item that has been tried in the game (its v0.1 / v0.2: write the slot, press Y for two polls):
// - The press goes into the engine's own button word, in a hook on the game's pad poll (0xa6d34c: pad, a1, &id, out; the
//   hook runs the poll, then ORs the press into out[0]), and is held for a number of POLLS (PRESS_POLLS), never for a time: a
//   long frame cannot swallow it. If that hook cannot be installed the same press is added to what GetNpadState gives instead
//   (the poll turns NpadButton bit 3 into 0x8000: the same thing). out and &id can be in main's image, below the heap, so they
//   are checked with plausible(), not sane().
// - The slot is written to the hunter's selected item (+0x3298) BEFORE the first press, whatever the weapon: where the game puts
//   a drawn weapon away and then uses the selected item by itself, it is the right item; where it only puts it away, the second
//   press (below) uses it.
//   weapon away: Y.  Sword & Shield out: R+Y (how the game uses an item with that weapon out, from Capcom's manual; RadialMenu does
//   the same, alternating with plain Y on a retry).  Any other weapon out: Y.
// - What counts as used. The item's count dropping, always (the slot was selected and Y pressed: that alone is NOT a use). For a
//   tool, whose count never drops: the hunter was idle at the press and its action changed (weapon away), or, with a Sword &
//   Shield R+Y, changed with the weapon still out. With another weapon out the hunter's action changing is no sign (it may only be
//   putting the weapon away): the use is then the count dropping, or, when the weapon is away and the hunter has been idle again
//   for SHEATHE_DONE_NS with nothing used, the press did no more than put it away: the slot is written again and Y pressed
//   again. Never a second press while the hunter is still doing the first thing: that would use an item twice.
// - Idle is the action pair u16 +0x2506 / +0x250a = 1:0 (hle/input_view.c); the weapon is out when the u8 at +0x114c is 1. A press
//   waits for idle, but only three seconds. "May use an item" is the item bar handler's own test (itembar.c): 0x47bcd0 == 1,
//   0x47b918 == 0 (bit 3 of the flags byte at +0x13cc; itembar.c and RadialMenu call it "busy") and vfunc +0x230 (player, 1) == 1.
// The use ends with an explicit result (the JSON's "result"): taken, or why not. Given up after ten seconds, or on leaving the
// quest. Not known yet (needs the game): that a Y reaches every weapon the same way, R+Y with the Sword & Shield, and with
// [Actions] Enable=1 the action layer may not pass the injected press on.
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
#define FN_PAD_POLL    0xa6d34cu       // the game's pad poll: (pad, a1, &id, out); out[0] is the engine's button word
#define ENG_Y          0x8000u         // the engine's button word (hle/input_view.c): Y, R
#define ENG_R          0x0200u
#define PRESS_POLLS    3               // pad polls a press is held (RadialMenu holds 2: the game takes a press from the polls)
#define USE_TAKEN_NS   800000000ull    // after a press: how long before "nothing happened" is believed
#define SHEATHE_DONE_NS 600000000ull   // weapon away and the hunter idle this long, nothing used: it only put the weapon away
#define IDLE_GRACE_NS  3000000000ull   // how long a press waits for the hunter to be idle (a Y in the middle of a move is lost)
#define USE_TRIES      4
#define BLOCK_NS       3000000000ull   // how long the game's own "may use an item" test may say no (with the hunter free) before the use is given up
#define RESULT_OK_NS   1500000000ull   // how long the panel is told a use went through
#define RESULT_BAD_NS  8000000000ull   // ... and why one did not (long enough to read it and tap again)
#define PIN_MAX        8               // pinned items: what the first page of the panel holds
#define BTN_Y_BIT      (1ull << 3)     // NpadButton Y, R (input.h): for the HID fallback
#define BTN_R_BIT      (1ull << 7)

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static char g_json[16384];
static size_t g_len;

// A use asked for: g_use_slot by any thread (-1: none), the rest by the game's thread only.
static int g_use_slot = -1;
enum { U_WAIT, U_SHEATHE, U_USE };
static volatile int u_slot = -1;  // (the panel's text is made on the same thread: volatile for the tests' sake)
static volatile int u_phase;
static int u_tries, u_count, u_press_left, u_polls;
static bool u_acted, u_after_sheathe, u_press_idle, u_ry, u_label_sheathe;
static uint32_t u_act_press, u_mask;
static uint64_t u_deadline, u_retry, u_wait_from, u_idle_since, u_blocked_since;
static bool g_engine_hook;  // the pad poll is hooked: the use is pressed there, not through GetNpadState
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
    u_slot = -1, u_press_left = 0;
}

// A use that is over, and how: told to the panel for a while (a failure so the player reads why and can tap again)
static void use_end(bool ok, const char *why, const char *code) {
    ULOG("slot %d: %s\n", u_slot, why);
    u_res.slot = u_slot, u_res.ok = ok, u_res.why = code;
    u_res.until = plat_now_ns() + (ok ? RESULT_OK_NS : RESULT_BAD_NS);
    u_slot = -1, u_press_left = 0;
}

static uint32_t hunter_act(uint32_t pl) { return ((uint32_t)LD16(pl + PL_ACT_ST) << 16) | LD16(pl + PL_ACT_NO); }

// One step of a use, once for each poll of player 1's pad: returns the engine's buttons to add to this poll (ENG_Y, ENG_R).
static uint32_t use_step(Cpu *c) {
    uint64_t now = plat_now_ns();
    pthread_mutex_lock(&g_lock);
    int asked = g_use_slot;
    g_use_slot = -1;
    pthread_mutex_unlock(&g_lock);
    if (asked >= 0) {
        u_slot = asked, u_phase = U_WAIT, u_tries = 0, u_acted = u_after_sheathe = u_label_sheathe = false;
        u_deadline = now + USE_TOTAL_NS, u_wait_from = now, u_press_left = 0;
        u_retry = u_idle_since = u_blocked_since = 0;
        u_wait_for = 0, u_res.slot = -1;
        ULOG("slot %d asked\n", asked);
    }
    if (u_slot < 0) return u_press_left = 0;
    uint32_t pl = local_player();
    if (!pl) return use_stop("left the quest"), 0;
    if (now >= u_deadline) return use_end(false, "gave up", "timeout"), 0;
    if (call(c, FN_PL_IS_LOCAL, pl, 0, 0) != 1) return use_stop("not our hunter"), 0;
    uint32_t pd = LD32(pl + PL_DATA), act = hunter_act(pl);
    bool drawn = LD8(pl + PL_DRAWN) != 0, sns = sane(pd) && LD32(pd + PD_WEAPON) == WEAPON_SNS;
    bool idle = act == ACT_IDLE;
    if (idle) {
        if (!u_idle_since) u_idle_since = now;
    } else u_idle_since = 0;
    if (u_phase != U_WAIT) {  // a press has been made: what has it done?
        u_polls++;
        if (act != u_act_press) u_acted = true;
        // Selecting the slot and pressing is not using the item: the count dropping is.
        if (pouch_count(u_slot) < u_count) return use_end(true, "taken: its count dropped", "taken"), 0;
        bool settled = now >= u_retry;  // (long enough after the press to believe that nothing happened)
        if (u_phase == U_USE) {  // the weapon was away: Y should have used the item
            if (u_acted && u_press_idle) return use_end(true, "taken: the hunter acted", "taken"), 0;  // (a tool's count never drops)
            if (settled && u_polls >= PRESS_POLLS) {
                if (u_tries >= USE_TRIES) return use_end(false, "the game did not take it", "ignored"), 0;
                u_phase = U_WAIT, u_wait_from = now;
            }
        } else {  // U_SHEATHE: the weapon was out at the press
            if (u_ry && u_acted && u_press_idle && drawn) return use_end(true, "taken: R+Y, the hunter acted, weapon still out", "taken"), 0;
            // idle for a while, nothing used: the press did no more than put the weapon away (or did nothing at all)
            if (settled && u_polls >= PRESS_POLLS && idle && u_idle_since && now - u_idle_since >= SHEATHE_DONE_NS) {
                if (u_tries >= USE_TRIES) {
                    if (drawn) use_end(false, "the weapon would not go away", "sheathe");
                    else use_end(false, "the item was not used", "ignored");
                    return 0;
                }
                u_after_sheathe = !drawn;  // (nothing was consumed: the slot is written again and Y pressed again)
                u_phase = U_WAIT, u_wait_from = now;
                ULOG("slot %d: %s\n", u_slot, drawn ? "nothing happened: again" : "the weapon is away, the item not used: again");
            }
        }
    }
    if (u_phase == U_WAIT) {
        if (pouch_count(u_slot) <= 0) return use_end(false, "none left", "empty"), 0;
        bool ready = idle || now - u_wait_from >= IDLE_GRACE_NS;  // (a press in the middle of a move is lost)
        u_wait_for = ready ? 1 : 0;
        if (ready) {
            // The game's own "may use an item" test. (The vtable is in main's image, below the heap: it is not sane(), and
            // asking for that made this test fail for ever: the use sat in "waiting" until it was given up.)
            uint32_t vt = LD32(pl), can = vt ? LD32(vt + VF_CAN_SELECT) : 0;
            if (call(c, FN_PL_FLAG3, pl, 0, 0) == 0 && can && call(c, can, pl, 1, 0) == 1) {
                u_blocked_since = 0;
                ST16(pl + PL_ITEM_SLOT, u_slot);  // select first, always
                u_count = pouch_count(u_slot);
                u_press_idle = idle, u_act_press = act, u_acted = false, u_polls = 0;
                u_ry = drawn && sns && (u_tries % 2 == 0);  // (a Sword & Shield out: R+Y first, plain Y on the retry)
                u_mask = ENG_Y | (u_ry ? ENG_R : 0);
                u_label_sheathe = drawn && !sns;
                u_phase = drawn ? U_SHEATHE : U_USE, u_tries++;
                u_retry = now + USE_TAKEN_NS, u_press_left = PRESS_POLLS;
                ULOG("slot %d: selected, %s pressed (try %d)%s\n", u_slot, u_ry ? "R+Y" : "Y", u_tries, drawn ? ", weapon out" : "");
            } else if (!u_blocked_since) {
                u_blocked_since = now;
                ULOG("slot %d: the game says an item can't be used now\n", u_slot);
            } else if (now - u_blocked_since >= BLOCK_NS) {
                return use_end(false, "the game's own test says no", "blocked"), 0;
            }
        }
    }
    if (u_press_left > 0) return u_press_left--, u_mask;
    return 0;
}

uint32_t companion_pad_bits(Cpu *c) { return use_step(c); }

// The HID fallback: only when the pad poll could not be hooked (the same press, an earlier way in).
uint64_t companion_buttons(Cpu *c) {
    if (g_engine_hook) return 0;
    uint32_t m = use_step(c);
    return ((m & ENG_Y) ? BTN_Y_BIT : 0) | ((m & ENG_R) ? BTN_R_BIT : 0);
}

static bool plausible(uint32_t a) { return a >= 0x00100000u && a < 0xF0000000u && !(a & 3); }  // (main's image too: not sane())

// The hook on the game's pad poll: runs it, then adds the press to the button word it made.
static void pad_poll(Cpu *c) {
    uint32_t id_ptr = c->r[2], out = c->r[3];
    fnhook_call_original(c, FN_PAD_POLL);
    // Player 1's pad only. The id is passed as a pointer in some builds and by value in RadialMenu's (which never reads it): both
    // are taken, as requiring a pointer here meant that when it is a plain number (0) the press was never added at all.
    uint32_t id = plausible(id_ptr) ? LD32(id_ptr) : id_ptr;
    if (!plausible(out) || id != 0) return;
    uint32_t add = use_step(c);
    if (add) ST32(out, LD32(out) | add);
}

void companion_init(void) {
    g_engine_hook = fnhook_set(FN_PAD_POLL, hle_register("companion:pad_poll", pad_poll));
    LOG("[companion] item presses: %s\n", g_engine_hook ? "in the game's pad poll (0xa6d34c)" : "through GetNpadState (the pad poll could not be hooked)");
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
            const char *step = u_phase != U_WAIT ? (u_label_sheathe ? "sheathe" : "use") : u_after_sheathe ? "sheathe" : "wait";
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
