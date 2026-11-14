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
    GC = 0x08001000, SP = 0x08002000, PL = 0x08010000, VT = 0x08003000, PD = 0x08020000, SQ = 0x08004000, QD = 0x08005000,
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

// The hunter: what a Y does, and what happens next
enum { A_NONE, A_SHEATHE, A_DRINK, A_BUSY };
static struct {
    int anim;                // what it is doing
    uint64_t anim_end;
    int flip_at_start;       // the weapon flag flips when the move starts (else when it ends)
    int stuck;               // the sheathing move never ends (the action stays non-idle)
    int ignore;              // the game takes no Y at all
    int drop_ms;             // the item's count drops this long after the press (-1: never, a tool)
    int sheathe_ms, drink_ms;
    uint64_t drop_at;
    int drop_slot;
} G;

static void game_step(int y_edge) {
    if (G.anim != A_NONE && g_now >= G.anim_end && !(G.anim == A_SHEATHE && G.stuck)) {
        if (G.anim == A_SHEATHE && !G.flip_at_start) ST8(PL + PL_DRAWN, 0);
        G.anim = A_NONE, set_act(1, 0);
    }
    if (G.drop_at && g_now >= G.drop_at) G.drop_at = 0, count_down(G.drop_slot);
    if (!y_edge || G.ignore || G.anim != A_NONE || LD16(PL + PL_ACT_ST) != 1 || LD16(PL + PL_ACT_NO) != 0) return;  // (a Y while not idle is lost)
    if (LD8(PL + PL_DRAWN) && LD32(PD + 0x14c) != SNS) {
        G.anim = A_SHEATHE, G.anim_end = g_now + (uint64_t)G.sheathe_ms * 1000000ull, set_act(0x10, 7);
        if (G.flip_at_start) ST8(PL + PL_DRAWN, 0);
    } else {
        G.anim = A_DRINK, G.anim_end = g_now + (uint64_t)G.drink_ms * 1000000ull, set_act(1, 5);
        if (G.drop_ms >= 0) G.drop_at = g_now + (uint64_t)G.drop_ms * 1000000ull, G.drop_slot = LD16(PL + PL_ITEM_SLOT);
    }
}

static int g_ys, g_prev;
static uint64_t g_press_at[16];
static uint64_t poll(void) {
    g_now += 16000000ull;
    uint64_t b = companion_buttons(&g_cpu);
    int edge = (b & 8) && !g_prev;
    if (edge && g_ys < 16) g_press_at[g_ys] = g_now;
    if (edge) g_ys++;
    g_prev = (b & 8) != 0;
    game_step(edge);
    return b;
}
static void run_ms(int ms) { for (int n = ms / 16; n-- > 0;) poll(); }
static void json(char *buf, size_t n) { g_now += 200000000ull; companion_tick(&g_cpu); assert(companion_snapshot(buf, n) > 0); }
static int using_now(void) { char t[16384]; json(t, sizeof t); return strstr(t, "\"using\":") != NULL; }
static int ms_between(int a, int b) { return (int)((g_press_at[b] - g_press_at[a]) / 1000000ull); }

static void weapon(uint32_t type, int drawn) { ST32(PD + 0x14c, type), ST8(PL + PL_DRAWN, drawn); }

static void reset(void) {
    memset(&G, 0, sizeof G);
    G.flip_at_start = 1, G.drop_ms = 300, G.sheathe_ms = 800, G.drink_ms = 1200;
    g_flag3 = 0, g_can = 1, g_local = 1;
    ST32(GC + 0x4c, 0xff), ST16(PL + PL_ITEM_SLOT, 0x28), set_act(1, 0), weapon(LONG_SWORD, 0);
    for (uint32_t s = 0; s < 0x32; s++) ST16(POUCH + 0x1a + 4 * s, 9 + s), ST16(POUCH + 0x1c + 4 * s, 10);
    run_ms(12000);  // (whatever was waiting runs out)
    G.anim = A_NONE, G.drop_at = 0, set_act(1, 0);
    g_ys = g_prev = 0;
    memset(g_press_at, 0, sizeof g_press_at);
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

// ---- a sheathed weapon: select the slot, press Y once, and the item is used once
static void test_sheathed(void) {
    reset();
    companion_use(5);
    run_ms(100);
    CHECK(LD16(PL + PL_ITEM_SLOT) == 5);
    CHECK(g_ys == 1);
    char t[16384];
    json(t, sizeof t);
    CHECK(strstr(t, "\"using\":5"));
    run_ms(4000);
    CHECK(g_ys == 1);
    CHECK(slot_count(5) == 9);
    CHECK(!using_now());
}

// ---- a tool (its count never drops) and a count that drops late: still one press, so one use
static void test_taken_signals(void) {
    reset();
    G.drop_ms = -1;
    companion_use(5);
    run_ms(4000);
    CHECK(g_ys == 1 && slot_count(5) == 10 && !using_now());  // the hunter acted: that is taken
    reset();
    G.drop_ms = 2000;  // the count drops after the move is over
    companion_use(5);
    run_ms(5000);
    CHECK(g_ys == 1 && slot_count(5) == 9 && !using_now());
}

// ---- a Sword & Shield out: the same, without putting it away
static void test_sns(void) {
    reset();
    weapon(SNS, 1);
    companion_use(7);
    run_ms(100);
    CHECK(LD16(PL + PL_ITEM_SLOT) == 7);
    CHECK(g_ys == 1);
    run_ms(4000);
    CHECK(g_ys == 1 && slot_count(7) == 9 && LD8(PL + PL_DRAWN) == 1 && !using_now());
}

// ---- any other weapon out: Y to put it away, then, once it is away and the hunter is idle again, select and Y again
static void test_drawn(int flip_at_start) {
    reset();
    G.flip_at_start = flip_at_start;
    weapon(LONG_SWORD, 1);
    ST16(PL + PL_ITEM_SLOT, 3);
    companion_use(5);
    run_ms(100);
    CHECK(g_ys == 1);                       // the first Y is for the weapon
    CHECK(LD16(PL + PL_ITEM_SLOT) == 3);    // the slot isn't picked yet
    char t[16384];
    json(t, sizeof t);
    CHECK(strstr(t, "\"step\":\"sheathe\""));
    // json() let 200 ms pass
    run_ms(600);
    CHECK(g_ys == 1);                       // nothing more while the move is on
    run_ms(3000);
    CHECK(g_ys == 2);
    CHECK(LD16(PL + PL_ITEM_SLOT) == 5);
    CHECK(LD8(PL + PL_DRAWN) == 0);
    CHECK(slot_count(5) == 9);
    CHECK(ms_between(0, 1) >= 800 + 150 - 20);  // not before the move is over and the hunter has been idle a moment
    CHECK(ms_between(0, 1) < 2500);
    CHECK(!using_now());
}

// ---- a move in progress (an attack): nothing is pressed until the hunter is idle
static void test_attack(void) {
    reset();
    weapon(LONG_SWORD, 1);
    G.anim = A_BUSY, G.anim_end = g_now + 1000ull * 1000000ull, set_act(0x10, 3);
    companion_use(3);
    run_ms(900);
    CHECK(g_ys == 0);
    run_ms(4000);
    CHECK(g_ys == 2 && slot_count(3) == 9 && LD8(PL + PL_DRAWN) == 0 && !using_now());
    CHECK(ms_between(0, 0) == 0);
    CHECK(g_press_at[0] > 0);

    // a hunter that is never idle (a stance, or the pair is not what we think): Y goes anyway after the grace
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

// ---- the sheathing move never seems to end: the use goes a second after the weapon is away, idle or not
static void test_stuck(void) {
    reset();
    weapon(LONG_SWORD, 1);
    G.stuck = 1;
    companion_use(5);
    run_ms(900);
    CHECK(g_ys == 1);
    run_ms(1500);
    CHECK(g_ys >= 2);  // (the model takes no Y while it is not idle, so the reader presses again a few times: that is right)
    CHECK(ms_between(0, 1) >= 950 && ms_between(0, 1) < 1400);
}

// ---- a game that takes no Y: pressed again a few times, then given up (and nothing is used)
static void test_ignored(void) {
    reset();
    G.ignore = 1;
    companion_use(5);
    run_ms(9500);
    CHECK(g_ys == 4);  // USE_TRIES
    CHECK(slot_count(5) == 10);
    CHECK(!using_now());
    reset();
    G.ignore = 1;
    weapon(LONG_SWORD, 1);
    companion_use(5);
    run_ms(9500);
    CHECK(g_ys == 3);  // SHEATHE_TRIES
    CHECK(LD16(PL + PL_ITEM_SLOT) == 0x28);
    CHECK(!using_now());
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
}

int main(void) {
    setup();
    test_timer();
    test_sheathed();
    test_taken_signals();
    test_sns();
    test_drawn(1);
    test_drawn(0);
    test_attack();
    test_stuck();
    test_ignored();
    test_stops();
    if (failed) { fprintf(stderr, "%d check(s) failed\n", failed); return 1; }
    printf("test-companion: all passed\n");
    return 0;
}
