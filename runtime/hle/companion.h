// The second screen's companion panel (AYN Thor): the game's state as a small JSON text (runtime/hle/companion.c)
#pragma once
#include <stddef.h>

#include "../cpu.h"

// From the game's input polling (the game's thread): reads the game's state ten times a second.
void companion_tick(Cpu *c);
// The last state read, as JSON (UTF-8, NUL-terminated) into buf; returns its length, 0 when there is none yet. Any thread.
size_t companion_snapshot(char *buf, size_t size);
