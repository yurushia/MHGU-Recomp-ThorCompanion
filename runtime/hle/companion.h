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
// Hooks the game's pad poll (0xa6d34c): a use is pressed into the engine's button word there, a number of polls at a time.
void companion_init(void);
// One step of a use per poll of player 1's pad (the game's thread): the engine buttons to add to that poll (0x8000 Y, 0x200 R).
uint32_t companion_pad_bits(Cpu *c);
// The same press for GetNpadState, used only when the pad poll could not be hooked (otherwise 0): NpadButton bits.
uint64_t companion_buttons(Cpu *c);
// The engine buttons the last pad poll had pressed for a use: the action layer adds them to the gameplay word it builds.
uint32_t companion_injected(void);
