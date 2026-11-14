// Host-only test of Settings > Duo Companion's PinnedItems through the real config code: read from the ini, edited, saved, read
// again after a "restart"; no game files needed (make test-companion-pins, next to test-settings).
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "config.h"
#ifdef _WIN32
#define set_env(key, value) _putenv_s(key, value)
#else
#define set_env(key, value) setenv(key, value, 1)
#endif

volatile uint64_t g_present_count;  // (runtime/ui.c's frame counter: config.c's model previews read it)
static char *slurp(const char *p) { FILE *f = fopen(p, "rb"); assert(f); fseek(f, 0, SEEK_END); long n = ftell(f); rewind(f); char *t = malloc(n + 1); assert(fread(t, 1, n, f) == (size_t)n); t[n] = 0; fclose(f); return t; }
int main(int argc, char **argv) {
    assert(argc == 2);
    FILE *f = fopen(argv[1], "wb"); assert(f);
    fputs("[Display]\nMode = Windowed\n[Companion]\nPinnedItems = 28, 9,10\n", f); fclose(f);
    set_env("MHGU_CONFIG", argv[1]);
    config_load();
    assert(!strcmp(config_companion_pins(), "28, 9,10"));          // read from the file
    Config c = config_editable();
    assert(!strcmp(c.companion_pins, "28, 9,10"));
    snprintf(c.companion_pins, sizeof c.companion_pins, "%s", "56,9");  // what the settings tab stores
    char err[256];
    if (!config_save(&c, err, sizeof err)) { fprintf(stderr, "save refused: %s\n", err); return 1; }
    char *t = slurp(argv[1]);
    assert(strstr(t, "PinnedItems = 56,9") != NULL);               // saved
    assert(strstr(t, "[Companion]") != NULL);
    free(t);
    config_load();                                                 // "restart"
    assert(!strcmp(config_companion_pins(), "56,9"));              // and back
    Config d = config_editable();
    snprintf(d.companion_pins, sizeof d.companion_pins, "%s", "");
    assert(config_save(&d, err, sizeof err));
    config_load();
    assert(!strcmp(config_companion_pins(), ""));                  // unpinned all, kept
    strcpy(d.companion_pins, "9;10");                              // a comment character: refused, as for any text setting
    assert(!config_save(&d, err, sizeof err));
    puts("pins round trip: all passed");
    return 0;
}
