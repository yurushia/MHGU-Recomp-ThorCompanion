package io.github.morerokk.mhgurecomp;

/** The test's stand-in for the app's Companion: it only records which items the panel asked to use. */
final class Companion {
    static final java.util.List<Integer> used = new java.util.ArrayList<>();

    static void useItem(int slot) {
        used.add(slot);
    }
}
