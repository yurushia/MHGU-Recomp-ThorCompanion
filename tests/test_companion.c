// Host-only test of the companion panel's reader (runtime/hle/companion.c): the quest timer, and the one-touch use of an
// item for a sheathed weapon, a Sword & Shield out and any other weapon out. No game files: the game's memory is made up
// here (a window mapped at GUEST_BASE), the few game functions the reader calls are answered by guest_call below, and a
// small model of the hunter reacts to the Y presses the reader makes: it puts a drawn weapon away or drinks, while it is
// idle only, with a count that drops early, late or never (a tool), and a weapon flag that flips at the start or the end of
// the move. A press the model did not take is lost, as in the game.
//   make test-companion      (Linux or WSL: the game's window is mapped with mmap)
#define _GNU_SOURCE
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>

#include "../runtime/hle.h"
#include "../runtime/hle/companion.h"

// ---- what the reader needs from the runtime
static uint64_t g_now = 1000000000ull;
uint64_t plat_now_ns(void) { return g_now; }
const char *presence_monster_name(uint16_t id) { (void)id; return "Monster"; }
static const char *g_pin_text = "";
const char *config_companion_pins(void) { return g_pin_text; }

static int g_flag3, g_local = 1, g_can = 1;
static const uint32_t g_bar[] = {3, 5, 7};  // the item bar's order, as the game's walk gives it
void guest_call(Cpu *c, uint32_t addr) {
    switch (addr) {
    case 0x47bcd0: c->r[0] = (uint32_t)g_local; break;        // is it ours
    case 0x47b918: c->r[0] = (uint32_t)g_flag3; break;        // flag bit 3 (must be 0)
    case 0x4a7ff4: c->r[0] = (uint32_t)g_can; break;          // may pick an item (vfunc +0x230)
    case 0x4880fc: {                                          // next slot on the bar
        uint32_t slot = c->r[1], next = 0x28;
        if (slot == 0x28) next = g_bar[0];
        for (size_t i = 0; i + 1 < sizeof g_bar / sizeof *g_bar; i++) if (g_bar[i] == slot) next = g_bar[i + 1];
        c->r[0] = next;
        break;
    }
    default: fprintf(stderr, "unexpected guest call %08x\n", addr); assert(0);
    }
}

// ---- the made-up game
enum {
    GC = 0x08001000, SP = 0x08002000, PL = 0x08010000, VT = 0x01a30000 /* (main's image: below the heap, not sane()) */, PD = 0x08020000, SQ = 0x08004000, QD = 0x08005000,
    RATE = 0x08006000, POUCH = 0x08007000
};
#define G_GAMECONTROL 0x1a8ca48u
#define G_SPLAYER 0x1a85b58u
#define G_SQUEST 0x1a8ad90u
#define G_POUCH 0x1a584b8u
#define G_RATE 0x2326764u
#define PL_DRAWN 0x114cu
#define PL_ACT_ST 0x2506u
#define PL_ACT_NO 0x250au
#define PL_ITEM_SLOT 0x3298u
#define SNS 1u
#define LONG_SWORD 7u

static void setup(void) {
    void *m = mmap((void *)GUEST_BASE, 0x10000000u, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE | MAP_NORESERVE, -1, 0);
    assert(m == (void *)GUEST_BASE);
    ST32(G_GAMECONTROL, GC), ST32(GC + 0x4c, 0xff), ST32(GC + 0x30, 0);
    ST32(G_SPLAYER, SP), ST32(SP + 0x1c, PL);
    ST32(PL, VT), ST32(VT + 0x230, 0x4a7ff4), ST32(VT + 0x5c4, 0x1234);
    ST32(PL + 0x13c4, PD), memcpy(G2H(PD + 0x514), "Theo", 5);
    ST32(G_SQUEST, SQ), ST32(SQ + 0xa4, QD), ST32(QD + 8, 101), ST8(QD + 0x10, 1);
    ST32(G_RATE, RATE), STF(RATE + 0x38, 30.0f);
    ST32(G_POUCH, POUCH);
    ST32(SQ + 0xcc, 50), ST32(SQ + 0x1c, 50 * 60 * 30);
}

static Cpu g_cpu;
static void set_act(uint16_t st, uint16_t no) { ST16(PL + PL_ACT_ST, st), ST16(PL + PL_ACT_NO, no); }
static int slot_count(int slot) { return (int16_t)LD16(POUCH + 0x1c + 4 * (uint32_t)slot); }
static void count_down(int slot) { ST16(POUCH + 0x1c + 4 * (uint32_t)slot, slot_count(slot) - 1); }

// The hunter: what a press does, and what happens next. Two things the game might do with a Y while a weapon is out are modelled,
// because which one it is cannot be told from the code: it only puts the weapon away (then a second press uses the item), or it
// puts it away and then uses the selected item by itself (auto_use). And a Sword & Shield out may need R+Y (sns_needs_r).
enum { A_NONE, A_SHEATHE, A_DRINK, A_BUSY };
static struct {
    int anim;                // what it is doing
    uint64_t anim_end;
    int flip_at_start;       // the weapon flag flips when the move starts (else when it ends)
    int stuck;               // the sheathing move never ends (the action stays non-idle)
    int ignore;              // the game takes no press at all (the slot still gets selected: that is the reader's write)
    int auto_use;            // a Y with the weapon out puts it away and then uses the slot that was selected at the press
    int idle_gap_ms;         // ... after this long idle
    int sns_needs_r;         // a Sword & Shield out uses an item only with R+Y (a Y alone puts it away)
    int drop_ms;             // the item's count drops this long after the use starts (-1: never, a tool)
    int sheathe_ms, drink_ms;
    int needs_polls;         // the game takes a press after this many consecutive polls with Y (default 1)
    uint64_t drop_at, use_at;
    int drop_slot, press_slot, pending_use;
    int used[8], n_used;     // the slot each use began with
} G;

static void start_drink(int slot) {
    G.anim = A_DRINK, G.anim_end = g_now + (uint64_t)G.drink_ms * 1000000ull, set_act(1, 5);
    if (G.n_used < 8) G.used[G.n_used++] = slot;
    if (G.drop_ms >= 0) G.drop_at = g_now + (uint64_t)G.drop_ms * 1000000ull, G.drop_slot = slot;
}

static void game_step(int y_edge, int r_down) {
    if (G.anim != A_NONE && g_now >= G.anim_end && !(G.anim == A_SHEATHE && G.stuck)) {
        int was = G.anim;
        if (was == A_SHEATHE && !G.flip_at_start) ST8(PL + PL_DRAWN, 0);
        G.anim = A_NONE, set_act(1, 0);
        if (was == A_SHEATHE && G.auto_use) G.pending_use = 1, G.use_at = g_now + (uint64_t)G.idle_gap_ms * 1000000ull;
    }
    if (G.pending_use && G.anim == A_NONE && g_now >= G.use_at) G.pending_use = 0, start_drink(G.press_slot);
    if (G.drop_at && g_now >= G.drop_at) G.drop_at = 0, count_down(G.drop_slot);
    if (!y_edge || G.ignore || G.anim != A_NONE || LD16(PL + PL_ACT_ST) != 1 || LD16(PL + PL_ACT_NO) != 0) return;  // (a press while not idle is lost)
    int drawn = LD8(PL + PL_DRAWN), sns = LD32(PD + 0x14c) == SNS;
    int uses_now = !drawn || (sns && (!G.sns_needs_r || r_down));
    if (uses_now) {
        start_drink(LD16(PL + PL_ITEM_SLOT));
    } else {
        G.anim = A_SHEATHE, G.anim_end = g_now + (uint64_t)G.sheathe_ms * 1000000ull, set_act(0x10, 7);
        G.press_slot = LD16(PL + PL_ITEM_SLOT);
        if (G.flip_at_start) ST8(PL + PL_DRAWN, 0);
    }
}

static int g_ys, g_rys, g_prev, g_gap_ms = 16, g_hid, g_yrun;
static uint64_t g_press_at[16];
static int g_slot_at_press[16], g_polls_with_y[16];
static uint32_t pad_mask(void) {  // the press this poll, as the engine's buttons, by the way in that is being tested
    if (!g_hid) return companion_pad_bits(&g_cpu);
    uint64_t b = companion_buttons(&g_cpu);  // (NpadButton: Y bit 3, R bit 7)
    return ((b & (1ull << 3)) ? 0x8000u : 0) | ((b & (1ull << 7)) ? 0x0200u : 0);
}
static void model_poll(uint32_t mask) {  // the game's side of one pad poll
    int y = (mask & 0x8000u) != 0, edge = y && !g_prev;
    if (edge && g_ys < 16) g_press_at[g_ys] = g_now, g_slot_at_press[g_ys] = LD16(PL + PL_ITEM_SLOT);
    if (y && g_ys < 16 && g_ys > 0 && !edge) g_polls_with_y[g_ys - 1]++;
    if (edge) {
        if (g_ys < 16) g_polls_with_y[g_ys] = 1;
        g_ys++;
        if (mask & 0x0200u) g_rys++;
    }
    g_prev = y;
    g_yrun = y ? g_yrun + 1 : 0;
    game_step(y && g_yrun == (G.needs_polls ? G.needs_polls : 1), (mask & 0x0200u) != 0);  // (taken on the poll that makes enough in a row)
}
static void poll(void) {
    g_now += (uint64_t)g_gap_ms * 1000000ull;
    model_poll(pad_mask());
}
static void run_ms(int ms) { for (int n = ms / 16; n-- > 0;) poll(); }
static void json(char *buf, size_t n) { g_now += 200000000ull; companion_tick(&g_cpu); assert(companion_snapshot(buf, n) > 0); }
static int has(const char *needle) { char t[16384]; json(t, sizeof t); return strstr(t, needle) != NULL; }
static int using_now(void) { return has("\"using\":"); }
static int ms_between(int a, int b) { return (int)((g_press_at[b] - g_press_at[a]) / 1000000ull); }

static void weapon(uint32_t type, int drawn) { ST32(PD + 0x14c, type), ST8(PL + PL_DRAWN, drawn); }

static void reset(void) {
    memset(&G, 0, sizeof G);
    G.flip_at_start = 1, G.drop_ms = 300, G.sheathe_ms = 800, G.drink_ms = 1200;
    g_flag3 = 0, g_can = 1, g_local = 1, g_gap_ms = 16;
    ST32(GC + 0x4c, 0xff), ST16(PL + PL_ITEM_SLOT, 0x28), set_act(1, 0), weapon(LONG_SWORD, 0);
    for (uint32_t s = 0; s < 0x32; s++) ST16(POUCH + 0x1a + 4 * s, 9 + s), ST16(POUCH + 0x1c + 4 * s, 10);
    run_ms(12000);  // (whatever was waiting runs out)
    G.anim = A_NONE, G.drop_at = 0, G.pending_use = 0, set_act(1, 0);
    g_ys = g_rys = g_prev = g_yrun = 0;
    memset(g_press_at, 0, sizeof g_press_at), memset(g_slot_at_press, 0, sizeof g_slot_at_press), memset(g_polls_with_y, 0, sizeof g_polls_with_y);
}

#define CHECK(cond) do { if (!(cond)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); failed++; } } while (0)
static int failed;

// ---- the timer
static void test_timer(void) {
    char t[16384];
    json(t, sizeof t);
    CHECK(strstr(t, "\"time\":{\"left\":3000,\"limit\":3000}"));  // 50 minutes at the start: counting down from the limit
    ST32(SQ + 0x1c, 25 * 60 * 30);
    json(t, sizeof t);
    CHECK(strstr(t, "\"left\":1500,"));
    int last = 1500;
    for (int i = 0; i < 5; i++) {  // a second later the time left is smaller, never larger
        ST32(SQ + 0x1c, LD32(SQ + 0x1c) - 30);
        json(t, sizeof t);
        int left = 0;
        const char *p = strstr(t, "\"left\":");
        CHECK(p && sscanf(p, "\"left\":%d", &left) == 1 && left == last - 1);
        last = left;
    }
    ST32(SQ + 0x1c, 0xfffffff0u);  // run out (or negative): not below nothing
    json(t, sizeof t);
    CHECK(strstr(t, "\"left\":0,"));
    ST32(SQ + 0x1c, 0x44610000u);  // the cheat's huge value: not above the limit
    json(t, sizeof t);
    CHECK(strstr(t, "\"left\":3000,"));
    ST32(SQ + 0x1c, 50 * 60 * 30);
}

// ---- a weapon that is away: the slot is selected, Y pressed for PRESS_POLLS polls, the item used once
static void test_sheathed(void) {
    reset();
    ST16(PL + PL_ITEM_SLOT, 3);  // another item was selected before
    companion_use(5);
    run_ms(100);
    CHECK(g_ys == 1);
    CHECK(g_slot_at_press[0] == 5);      // selected BEFORE the press, not after
    CHECK(g_polls_with_y[0] >= 1 && g_polls_with_y[0] <= 3);   // held for polls (stopped as soon as the hunter has acted)
    run_ms(400);
    CHECK(has("\"result\":{\"slot\":5,\"ok\":true,\"why\":\"taken\"}"));
    run_ms(4000);
    CHECK(g_ys == 1 && g_rys == 0);
    CHECK(slot_count(5) == 9 && slot_count(3) == 10);  // the tapped one, once
    CHECK(G.n_used == 1 && G.used[0] == 5);
    CHECK(!using_now());
}

// ---- THE COMPLAINT: the slot got selected but the game never used the item. That is a failure, never a success.
static void test_selected_is_not_used(void) {
    reset();
    G.ignore = 1;
    companion_use(5);
    run_ms(10000);
    CHECK(LD16(PL + PL_ITEM_SLOT) == 5);          // (it was selected, as the player saw)
    CHECK(slot_count(5) == 10);                   // (and not used)
    CHECK(has("\"result\":{\"slot\":5,\"ok\":false,\"why\":\"ignored\"}"));
    CHECK(!has("\"ok\":true"));
    CHECK(g_ys == 4);                             // (a few presses, not an endless stream)
    G.ignore = 0;                                 // the game takes presses again: tapping again works, once
    companion_use(5);
    run_ms(600);
    CHECK(has("\"ok\":true") && !has("\"ok\":false"));   // (the new tap cleared the old failure)
    run_ms(2500);
    CHECK(slot_count(5) == 9 && G.n_used == 1 && g_ys == 5);
}

// ---- a tool (its count never drops) and a count that drops late: still one press, so one use
static void test_taken_signals(void) {
    reset();
    G.drop_ms = -1;
    companion_use(5);
    run_ms(600);
    CHECK(has("\"why\":\"taken\""));    // the hunter acted from idle: that is taken
    run_ms(3400);
    CHECK(g_ys == 1 && slot_count(5) == 10 && !using_now());
    reset();
    G.drop_ms = 2000;  // the count drops after the move is over
    companion_use(5);
    run_ms(5000);
    CHECK(g_ys == 1 && slot_count(5) == 9 && !using_now() && G.n_used == 1);
}

// ---- a Sword & Shield out: R+Y, the weapon stays out
static void test_sns(void) {
    reset();
    G.sns_needs_r = 1;
    weapon(SNS, 1);
    companion_use(7);
    run_ms(100);
    CHECK(g_ys == 1 && g_rys == 1);          // R together with Y
    CHECK(g_slot_at_press[0] == 7);
    run_ms(4000);
    CHECK(g_ys == 1 && slot_count(7) == 9 && LD8(PL + PL_DRAWN) == 1 && !using_now() && G.n_used == 1);
    reset();  // a game where a plain Y does it: R+Y does it too
    weapon(SNS, 1);
    companion_use(7);
    run_ms(4000);
    CHECK(g_ys == 1 && slot_count(7) == 9 && LD8(PL + PL_DRAWN) == 1);
    reset();  // a tool with the weapon out: taken because the hunter acted and the weapon is still out
    G.sns_needs_r = 1, G.drop_ms = -1;
    weapon(SNS, 1);
    companion_use(7);
    run_ms(4000);
    CHECK(g_ys == 1 && slot_count(7) == 10 && !using_now() && has("\"why\":\"taken\"") == 0 /* (result shown 1.5 s: it is gone) */);
}

// ---- any other weapon out, where the game only puts it away: a second press uses the item, once the hunter has been idle a while
static void test_drawn_sheathe_only(int flip_at_start) {
    reset();
    G.flip_at_start = flip_at_start;
    weapon(LONG_SWORD, 1);
    ST16(PL + PL_ITEM_SLOT, 3);
    companion_use(5);
    run_ms(100);
    CHECK(g_ys == 1 && g_slot_at_press[0] == 5);   // the item is selected before the first press, whatever the weapon
    char t[16384];
    json(t, sizeof t);
    CHECK(strstr(t, "\"step\":\"sheathe\""));
    run_ms(2200);
    CHECK(g_ys == 2 && g_slot_at_press[1] == 5);   // again, written again
    CHECK(G.n_used == 1 && G.used[0] == 5);         // used once, the right one
    run_ms(3000);
    CHECK(g_ys == 2 && slot_count(5) == 9 && slot_count(3) == 10 && LD8(PL + PL_DRAWN) == 0 && !using_now());
    CHECK(ms_between(0, 1) >= 800 + 600 - 40);      // not before the move is over and the hunter has been idle a while
    CHECK(ms_between(0, 1) < 2500);
}

// ---- any other weapon out, where the game puts it away and uses the selected item by itself: ONE press, the right item, used once
static void test_drawn_auto_use(int idle_gap_ms) {
    reset();
    G.auto_use = 1, G.idle_gap_ms = idle_gap_ms;
    weapon(LONG_SWORD, 1);
    ST16(PL + PL_ITEM_SLOT, 3);
    companion_use(5);
    run_ms(8000);
    CHECK(g_ys == 1);                               // no second press: that would use an item twice
    CHECK(G.n_used == 1 && G.used[0] == 5);         // the tapped item, not the one that was selected before
    CHECK(slot_count(5) == 9 && slot_count(3) == 10);
    CHECK(!using_now());
}

// ---- a move in progress (an attack): nothing is pressed until the hunter is idle; a hunter that is never idle gets the press after the grace
static void test_attack(void) {
    reset();
    G.auto_use = 1;
    weapon(LONG_SWORD, 1);
    G.anim = A_BUSY, G.anim_end = g_now + 1000ull * 1000000ull, set_act(0x10, 3);
    companion_use(3);
    run_ms(900);
    CHECK(g_ys == 0);
    run_ms(5000);
    CHECK(g_ys == 1 && slot_count(3) == 9 && G.n_used == 1 && !using_now());

    reset();
    weapon(LONG_SWORD, 1);
    set_act(0x10, 3);
    G.ignore = 1;
    companion_use(3);
    run_ms(2800);
    CHECK(g_ys == 0);
    run_ms(500);
    CHECK(g_ys == 1);
}

// ---- the first press began something that never ends: no second press on top of it, and an explicit result at the end
static void test_stuck(void) {
    reset();
    weapon(LONG_SWORD, 1);
    G.stuck = 1;
    companion_use(5);
    run_ms(12000);
    CHECK(g_ys == 1);
    CHECK(has("\"why\":\"timeout\"") && has("\"ok\":false"));
}

// ---- a game that takes no press: pressed a few times, then a failure that says so (sheathed: "ignored"; weapon out: "sheathe")
static void test_ignored(void) {
    reset();
    G.ignore = 1;
    companion_use(5);
    run_ms(9500);
    CHECK(g_ys == 4);
    CHECK(slot_count(5) == 10 && !using_now());
    for (int i = 0; i < 4; i++) CHECK(g_slot_at_press[i] == 5 && g_polls_with_y[i] == 3);
    reset();
    G.ignore = 1;
    weapon(LONG_SWORD, 1);
    companion_use(5);
    run_ms(9500);
    CHECK(g_ys == 4 && has("\"why\":\"sheathe\""));
}

// ---- a long frame between two polls cannot swallow a press: it is held for polls, not for a time
static void test_hitch(void) {
    reset();
    G.needs_polls = 2;        // the game takes a press only from two polls in a row
    companion_use(5);
    for (int i = 0; i < 6; i++) poll();
    g_gap_ms = 400;           // every poll from here comes 400 ms after the one before (shader compiles, a loading hitch)
    for (int i = 0; i < 12; i++) poll();
    g_gap_ms = 16;
    run_ms(3000);
    CHECK(g_ys == 1 && g_polls_with_y[0] >= 2 && g_polls_with_y[0] <= 3);
    CHECK(slot_count(5) == 9 && G.n_used == 1);   // (a press held for 100 ms of time would have been seen once, or not at all)
}

// ---- an item that is gone, the quest ending, another touch meanwhile, and touches that are not an item
static void test_stops(void) {
    reset();
    ST16(POUCH + 0x1c + 4 * 5, 0);  // none left
    companion_use(5);
    run_ms(500);
    CHECK(g_ys == 0 && !using_now());

    reset();
    set_act(0x10, 3);
    G.anim = A_BUSY, G.anim_end = g_now + 2000ull * 1000000ull;
    companion_use(5);
    run_ms(200);
    ST32(GC + 0x4c, 2);  // back in a village
    run_ms(100);
    CHECK(!using_now());
    ST32(GC + 0x4c, 0xff);
    run_ms(3000);
    CHECK(g_ys == 0);  // forgotten, not resumed

    reset();
    G.sns_needs_r = 1;
    weapon(SNS, 1);
    set_act(0x10, 3);
    G.anim = A_BUSY, G.anim_end = g_now + 500ull * 1000000ull;
    companion_use(5);
    run_ms(100);
    companion_use(7);  // another touch: this one replaces it
    run_ms(3000);
    CHECK(LD16(PL + PL_ITEM_SLOT) == 7);
    CHECK(g_ys == 1 && slot_count(7) == 9 && slot_count(5) == 10);

    reset();
    companion_use(0x28);  // not an item
    companion_use(-1);
    companion_use(99);
    run_ms(500);
    CHECK(g_ys == 0);

    reset();
    g_flag3 = 1;  // the game's own flag says the bar cannot be used now
    companion_use(5);
    run_ms(2500);
    CHECK(g_ys == 0);
    run_ms(1000);
    CHECK(!using_now());  // (given up after three seconds, with a result that says why: test_results)
}

// ---- how a use ends is told to the panel: a clear result, never a "waiting" that just stops
static void test_results(void) {
    // it went through
    reset();
    companion_use(5);
    run_ms(600);
    CHECK(has("\"result\":{\"slot\":5,\"ok\":true,\"why\":\"taken\"}") && !has("\"using\""));
    run_ms(2000);
    CHECK(slot_count(5) == 9);
    CHECK(!has("\"result\""));  // (a success is shown for a moment only)

    // the game's own test says no with the hunter free: "waiting for the bar", then a failure that says so, and tapping again works
    reset();
    g_flag3 = 1;
    companion_use(5);
    run_ms(1000);
    CHECK(has("\"using\":5,\"step\":\"wait\",\"waitFor\":\"bar\""));
    run_ms(3500);
    CHECK(!has("\"using\""));
    CHECK(has("\"result\":{\"slot\":5,\"ok\":false,\"why\":\"blocked\"}"));
    CHECK(g_ys == 0 && slot_count(5) == 10);
    g_flag3 = 0;
    companion_use(5);  // a retry
    run_ms(1000);
    CHECK(slot_count(5) == 9 && g_ys == 1);
    CHECK(has("\"ok\":true") && !has("\"ok\":false"));  // (the new use cleared the old failure)

    // the vfunc says no
    reset();
    g_can = 0;
    companion_use(5);
    run_ms(4500);
    CHECK(has("\"why\":\"blocked\"") && !has("\"using\""));

    // while the hunter is busy it is "waiting for the hunter", not for the bar
    reset();
    set_act(0x10, 3);
    G.anim = A_BUSY, G.anim_end = g_now + 2000ull * 1000000ull;
    companion_use(5);
    run_ms(300);
    CHECK(has("\"waitFor\":\"hunter\""));

    // none left
    reset();
    ST16(POUCH + 0x1c + 4 * 5, 0);
    companion_use(5);
    run_ms(300);
    CHECK(has("\"why\":\"empty\""));

    // a failure stays long enough to read, then goes; a new tap clears it at once
    reset();
    ST16(POUCH + 0x1c + 4 * 5, 0);
    companion_use(5);
    run_ms(300);
    run_ms(5000);
    CHECK(has("\"result\""));
    run_ms(4000);
    CHECK(!has("\"result\""));
    ST16(POUCH + 0x1c + 4 * 5, 0);
    companion_use(5);
    run_ms(100);
    companion_use(7);
    run_ms(100);
    CHECK(!has("\"why\":\"empty\",") || has("\"slot\":7"));
}

// ---- the way in. By the HID layer (the fallback) a press is NpadButton Y / R; through the hook it goes into the engine's word.
static void test_hid_fallback(void) {
    g_hid = 1;
    reset();
    companion_use(5);
    run_ms(4000);
    CHECK(g_ys == 1 && g_polls_with_y[0] >= 1 && g_polls_with_y[0] <= 3 && slot_count(5) == 9);
    reset();
    G.sns_needs_r = 1;
    weapon(SNS, 1);
    companion_use(7);
    run_ms(4000);
    CHECK(g_ys == 1 && g_rys == 1 && slot_count(7) == 9);   // R+Y by the fallback too
    g_hid = 0;
}

// ---- the hook on the game's pad poll: the real function, run the way the dispatcher runs it
static int g_hook_ok = 1, g_orig_calls;
static HostFn g_hook_fn;
static uint32_t g_orig_word;
uint32_t hle_register(const char *name, HostFn fn) { (void)name; g_hook_fn = fn; return 0x03000010u; }
bool fnhook_set(uint32_t addr, uint32_t detour) { (void)detour; assert(addr == 0xa6d34cu); return g_hook_ok != 0; }
void fnhook_call_original(Cpu *c, uint32_t addr) {  // the game's poll: it makes the button word (here: A held) at out
    assert(addr == 0xa6d34cu);
    g_orig_calls++;
    ST32(c->r[3], g_orig_word);
}
static void test_pad_hook(void) {
    enum { OUT = 0x01a40000, IDP = 0x01a40100, OUT2 = 0x08300000, IDP2 = 0x08300100 };  // (main's image too: below the heap)
    // not hooked (could not be): the HID way is used
    g_hook_ok = 0;
    companion_init();
    reset();
    companion_use(5);
    uint64_t b = 0;
    for (int i = 0; i < 6 && !(b & (1ull << 3)); i++) g_now += 16000000ull, b = companion_buttons(&g_cpu);
    CHECK(b & (1ull << 3));
    run_ms(3000);
    // hooked: the HID way gives nothing, and the poll hook does the pressing
    g_hook_ok = 1;
    companion_init();
    CHECK(g_hook_fn != NULL);
    reset();
    ST32(IDP, 0), ST32(IDP2, 0);
    g_orig_word = 0x2000;  // A is held on the pad
    companion_use(5);
    CHECK(companion_buttons(&g_cpu) == 0);   // (the HID way is off)
    int polls_y = 0, edges = 0, prev = 0, a_kept = 1;
    for (int i = 0; i < 400; i++) {
        g_now += 16000000ull;
        uint32_t out = (i & 1) ? OUT2 : OUT, idp = (i & 1) ? IDP2 : IDP;   // (any address the game's poll uses)
        g_cpu.r[2] = idp, g_cpu.r[3] = out;
        g_hook_fn(&g_cpu);
        uint32_t w = LD32(out);
        int y = (w & 0x8000u) != 0;
        polls_y += y, edges += y && !prev, prev = y;
        a_kept &= (w & 0x2000u) != 0;
        model_poll(w & ~0x2000u);
    }
    CHECK(edges == 1 && polls_y >= 1 && polls_y <= 3);        // one press, held for polls (stopped once the hunter has acted)
    CHECK(a_kept);                            // what the pad had is kept
    CHECK(slot_count(5) == 9 && G.n_used == 1 && g_slot_at_press[0] == 5);
    CHECK(g_orig_calls == 400);               // the game's own poll ran every time
    // another pad (not player 1): untouched, and the use does not advance on it
    reset();
    companion_use(5);
    ST32(IDP, 0x20);
    g_orig_word = 0;
    for (int i = 0; i < 100; i++) {
        g_cpu.r[2] = IDP, g_cpu.r[3] = OUT;
        g_hook_fn(&g_cpu);
        CHECK(LD32(OUT) == 0);
    }
    ST32(IDP, 0);
    // the id passed by value (RadialMenu's hook never reads it as a pointer): player 1 is 0, and the press still goes in
    reset();
    ST16(POUCH + 0x1c + 4 * 5, 10), G.n_used = 0;  // (a use left waiting by the test before ran out in reset())
    companion_use(5);
    g_orig_word = 0;
    int by_value_y = 0;
    for (int i = 0; i < 200; i++) {
        g_now += 16000000ull;
        g_cpu.r[2] = 0, g_cpu.r[3] = OUT;
        g_hook_fn(&g_cpu);
        uint32_t w = LD32(OUT);
        by_value_y += (w & 0x8000u) != 0;
        model_poll(w);
    }
    CHECK(by_value_y >= 1 && slot_count(5) == 9);
    // and a pad that is not player 1, by value, is left alone
    reset();
    companion_use(5);
    g_orig_word = 0;
    for (int i = 0; i < 100; i++) {
        g_cpu.r[2] = 0x20, g_cpu.r[3] = OUT;
        g_hook_fn(&g_cpu);
        CHECK(LD32(OUT) == 0);
    }
    // nothing asked: the poll's word is left exactly as it was
    reset();
    g_orig_word = 0x1234;
    g_cpu.r[2] = IDP, g_cpu.r[3] = OUT;
    g_hook_fn(&g_cpu);
    CHECK(LD32(OUT) == 0x1234);
}

// ---- the pinned items: by number, in their order, no repeats, no rubbish, at most a page's worth
static void test_pins(void) {
    reset();
    g_pin_text = "";
    CHECK(has("\"pinMax\":8,\"pins\":[]"));
    g_pin_text = "28, 9,9,abc,0,70000,10";
    CHECK(has("\"pins\":[{\"id\":28,\"name\":\"Max Potion\"},{\"id\":9,\"name\":\"Potion\"},{\"id\":10,\"name\":\"Mega Potion\"}]"));
    g_pin_text = "9,10,11,12,13,14,15,16,17,18";
    char t[16384];
    json(t, sizeof t);
    CHECK(strstr(t, "{\"id\":16,") && !strstr(t, "{\"id\":17,"));  // (eight)
    g_pin_text = "";
    CHECK(has("\"pins\":[]"));
}

// ---- monsters' positions: real ones as they are, and none (not 0, 0, 0) when the game has none
enum { SE = 0x08008000, EN = 0x08009000, INFO = 0x0800a000 };
#define G_SENEMY 0x1a51fe0u
static void monster(int k, uint32_t number, uint8_t zone, float x, float y, float z, int hp) {
    uint32_t en = EN + 0x10000 * (uint32_t)k, info = INFO + 0x10000 * (uint32_t)k;
    ST32(SE + 0x1c + 4 * (uint32_t)k, en), ST32(en + 0x1428, info), ST32(en + 0xb5f4, number), ST8(en + 0x1054, zone);
    ST16(info + 0x1b6, 62), ST32(info + 0x370, (uint32_t)hp), ST32(info + 0x374, 4000);
    STF(info + 0x40, x), STF(info + 0x44, y), STF(info + 0x48, z);
}
static void test_positions(void) {
    reset();
    ST32(G_SENEMY, SE);
    monster(0, 2, 4, 1234.5f, 10.0f, -2345.5f, 3000);
    monster(1, 3, 7, 0.0f, 0.0f, 0.0f, 3000);          // not set up yet
    monster(2, 4, 4, 1.0f / 0.0f, 0.0f, 5.0f, 3000);   // not a number
    char t[16384];
    json(t, sizeof t);
    char *m0 = strstr(t, "\"zone\":4,\"hp\":3000,\"hpMax\":4000,\"x\":1234.5,\"y\":10.0,\"z\":-2345.5}");
    CHECK(m0 != NULL);
    CHECK(strstr(t, "\"zone\":7,\"hp\":3000,\"hpMax\":4000}") != NULL);   // (no x, y, z: not "0, 0, 0")
    CHECK(strstr(t, "\"zone\":4,\"hp\":3000,\"hpMax\":4000}") != NULL);   // (the infinite one)
    char *ms = strstr(t, "\"monsters\":["), *me = ms ? strstr(ms, "\"items\":") : NULL;
    CHECK(ms && me);
    for (char *q = ms; q && me && q < me; q++) CHECK(strncmp(q, "\"x\":0.0", 7) != 0 && strncmp(q, "inf", 3) != 0 && strncmp(q, "nan", 3) != 0);
    // the hunter: a real position as it is; one that is not a number, none
    STF(PL + 0x40, 10.0f), STF(PL + 0x44, 2.0f), STF(PL + 0x48, -7.0f);
    CHECK(has("\"x\":10.0,\"y\":2.0,\"z\":-7.0}"));
    STF(PL + 0x40, 0.0f / 0.0f);
    json(t, sizeof t);
    char *h = strstr(t, "\"hunter\":{");
    CHECK(h != NULL && (strstr(h, "\"x\":") == NULL || strstr(h, "\"x\":") > strstr(h, "\"monsters\"")));  // (none in the hunter's)
    ST32(G_SENEMY, 0);
}

int main(void) {
    setup();
    test_timer();
    test_sheathed();
    test_selected_is_not_used();
    test_taken_signals();
    test_sns();
    test_drawn_sheathe_only(1);
    test_drawn_sheathe_only(0);
    test_drawn_auto_use(0);
    test_drawn_auto_use(100);
    test_drawn_auto_use(400);
    test_attack();
    test_stuck();
    test_ignored();
    test_hitch();
    test_stops();
    test_results();
    test_hid_fallback();
    test_pins();
    test_positions();
    test_pad_hook();  // (last: it hooks the poll, which turns the HID way off)
    if (failed) { fprintf(stderr, "%d check(s) failed\n", failed); return 1; }
    printf("test-companion: all passed\n");
    return 0;
}
