// The second screen's companion panel (AYN Thor): the game's state as a small JSON text (runtime/hle/companion.c)
#pragma once
#include <stddef.h>
#include <stdint.h>

#include "../cpu.h"

// From the game's input polling (the game's thread): reads the game's state ten times a second.
void companion_tick(Cpu *c);
// The last state read, as JSON (UTF-8, NUL-terminated) into buf; returns its length, 0 when there is none yet. Any thread.
size_t companion_snapshot(char *buf, size_t size);
// Use the item in a pouch slot (as the JSON's items give it) as soon as the hunter may: any thread.
void companion_use(int slot);
// From GetNpadState for player 1 (the game's thread): steps a waiting use; the buttons to add to the pad meanwhile.
uint64_t companion_buttons(Cpu *c);
