// The shape of a quest area, for the companion panel's map (CompanionView): the area's ground (and water) collision, seen
// from above, read from the player's own copy of the game: romfs/nativeNX/loc/arc/stage/map_com/m<stage>.arc holds, for
// every area, "stage/m01d/m01a04d/collision/m01a04d-a-g00" (the ground) and "...-a-w00" (the water), in the same world
// coordinates the hunter's and the monsters' positions are in (runtime/hle/companion.c). Nothing of the game is kept in the
// app or the repository: the triangles are read from the player's files when an area is entered, and dropped with the app.
//
// The files ("SBC", one per collision group), little endian, as found by reading them (all 12 areas of Jurassic Frontier):
//   +0x00 "SBC" 0xff        +0x18 u32 triangle count        +0x1c u32 vertex count
//   a run of triangle records, 32 bytes each:  +0x0c normal (3 floats, unit length)  +0x18 u16 vertex a, b, c  +0x1e u16 material
//   then 12 bytes, then the vertices, 16 bytes each: x, y, z (floats, y is up) and 0.
// (before the triangles is a bounding volume tree that is not needed here.) The run is found by looking for the first place
// where `triangle count` records in a row make sense, so a file that is laid out a little differently is skipped, not guessed.
// The first part of the run is the area's box made of big triangles (its corners are the +0x30 and +0x40 points: the world's
// bounds, +-10000 in most areas): those are left out, the ones that touch the box, and what is left is the ground.
package io.github.morerokk.mhgurecomp;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

final class AreaMap {
    /** The game's romfs/nativeNX folder (set by Companion); null when the game's files aren't known. */
    static volatile File root;

    /**
     * The up-facing triangles of a collision file, seen from above. A file has the playable floor and, in the same list, the
     * terrain around it and the tops of rocks, at other heights: they are told apart by what is joined to what. Triangles that
     * share a corner are one piece (a cliff is a wall, which is left out, so the floor and the hills above it are two pieces):
     * comp[i] is the piece of triangle i, and the piece a hunter or a monster stands on is the one to show.
     */
    static final class Mesh {
        /** x z of the three corners, six floats a triangle. */
        final float[] xz;
        /** Each triangle's height (the mean of its corners). */
        final float[] y;
        /** Each triangle's piece, 0 .. pieces - 1. */
        final int[] comp;
        final int pieces;

        Mesh(float[] xz, float[] y, int[] comp, int pieces) {
            this.xz = xz;
            this.y = y;
            this.comp = comp;
            this.pieces = pieces;
        }

        int count() {
            return y.length;
        }

        /** The piece under a point (x, z at height y): the triangle that contains it and is nearest in height, within 600; -1 if none. */
        int pieceAt(float x, float yy, float z) {
            int best = -1;
            float gap = 600;
            for (int i = 0; i < y.length; i++) {
                float d = Math.abs(y[i] - yy);
                if (d >= gap) continue;
                int o = i * 6;
                if (inside(x, z, xz[o], xz[o + 1], xz[o + 2], xz[o + 3], xz[o + 4], xz[o + 5])) {
                    gap = d;
                    best = comp[i];
                }
            }
            return best;
        }

        private static boolean inside(float px, float pz, float ax, float az, float bx, float bz, float cx, float cz) {
            float d1 = (px - bx) * (az - bz) - (ax - bx) * (pz - bz);
            float d2 = (px - cx) * (bz - cz) - (bx - cx) * (pz - cz);
            float d3 = (px - ax) * (cz - az) - (cx - ax) * (pz - az);
            boolean neg = d1 < 0 || d2 < 0 || d3 < 0, pos = d1 > 0 || d2 > 0 || d3 > 0;
            return !(neg && pos);
        }
    }

    final Mesh ground, water;
    /** The whole of the ground's extent (the water's when there is no ground), in world units. */
    final float minX, maxX, minZ, maxZ;

    private AreaMap(Mesh ground, Mesh water) {
        this.ground = ground;
        this.water = water;
        float[] b = bounds(ground.count() > 0 ? ground : water, null);
        minX = b[0];
        maxX = b[1];
        minZ = b[2];
        maxZ = b[3];
    }

    /** The extent of the triangles of the pieces marked in `only` (all of them when null): minX, maxX, minZ, maxZ. */
    static float[] bounds(Mesh m, boolean[] only) {
        float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, z0 = Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
        for (int i = 0; i < m.count(); i++) {
            if (only != null && !only[m.comp[i]]) continue;
            for (int k = 0; k < 6; k += 2) {
                x0 = Math.min(x0, m.xz[i * 6 + k]);
                x1 = Math.max(x1, m.xz[i * 6 + k]);
                z0 = Math.min(z0, m.xz[i * 6 + k + 1]);
                z1 = Math.max(z1, m.xz[i * 6 + k + 1]);
            }
        }
        return x0 > x1 ? new float[] {0, 1, 0, 1} : new float[] {x0, x1, z0, z1};
    }

    // ---- loading (any thread; a few areas are kept, a miss too, so going back and forth reads nothing again)

    private static final AreaMap NONE = new AreaMap(new Mesh(new float[0], new float[0], new int[0], 0), new Mesh(new float[0], new float[0], new int[0], 0));
    private static final HashMap<Integer, AreaMap> cache = new HashMap<>();

    /** The area `zone` of stage `stage` (the quest's stage number: 1 is m01), null when the files aren't there or aren't understood. */
    static AreaMap load(int stage, int zone) {
        if (root == null) return null;  // (not known yet: not remembered as a miss)
        int key = stage * 100 + zone;
        synchronized (cache) {
            AreaMap a = cache.get(key);
            if (a != null) return a == NONE ? null : a;
        }
        AreaMap a = null;
        try {
            a = read(stage, zone);
        } catch (IOException | RuntimeException e) {
            // (a file that is not what it is expected to be)
        }
        synchronized (cache) {
            if (cache.size() > 8) cache.clear();
            cache.put(key, a != null ? a : NONE);
        }
        return a;
    }

    private static AreaMap read(int stage, int zone) throws IOException {
        File r = root;
        if (r == null || stage < 1 || stage > 99 || zone < 0 || zone > 99) return null;
        File arc = new File(r, String.format("loc/arc/stage/map_com/m%02d.arc", stage));
        if (!arc.isFile()) return null;
        String area = String.format("/m%02da%02d", stage, zone);
        try (RandomAccessFile f = new RandomAccessFile(arc, "r")) {
            byte[] head = new byte[12];
            f.readFully(head);
            ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            int n = h.getShort(6) & 0xffff;
            if (head[0] != 'A' || head[1] != 'R' || head[2] != 'C' || n < 1 || n > 20000) return null;
            byte[] table = new byte[n * 80];
            f.readFully(table);
            ByteBuffer t = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
            byte[] g = null, w = null;
            int gRank = 9, wRank = 9;  // (the day variant "d" first, then whatever there is)
            for (int i = 0; i < n; i++) {
                String name = entryName(table, i * 80);
                if (!name.contains("/collision/") || !name.contains(area)) continue;
                boolean isGround = name.endsWith("-a-g00"), isWater = name.endsWith("-a-w00");
                if (!isGround && !isWater) continue;
                int rank = name.contains(area + "d/") ? 0 : 1;
                if (isGround ? rank >= gRank : rank >= wRank) continue;
                byte[] data = entry(f, t, i * 80 + 64);
                if (data == null) continue;
                if (isGround) { g = data; gRank = rank; } else { w = data; wRank = rank; }
            }
            Mesh gt = g != null ? faces(g) : null, wt = w != null ? faces(w) : null;
            if ((gt == null || gt.count() == 0) && (wt == null || wt.count() == 0)) return null;
            Mesh none = NONE.ground;
            return new AreaMap(gt != null ? gt : none, wt != null ? wt : none);
        }
    }

    private static String entryName(byte[] table, int o) {
        int e = 0;
        while (e < 64 && table[o + e] != 0) e++;
        return "/" + new String(table, o, e, java.nio.charset.StandardCharsets.ISO_8859_1).replace('\\', '/');
    }

    // The entry whose record (type, compressed size, size and flags, offset) starts at t's position `at`
    private static byte[] entry(RandomAccessFile f, ByteBuffer t, int at) throws IOException {
        long csize = t.getInt(at + 4) & 0xffffffffL, size = t.getInt(at + 8) & 0x1fffffffL, off = t.getInt(at + 12) & 0xffffffffL;
        if (csize <= 0 || csize > (64 << 20) || size <= 0 || size > (64 << 20) || off + csize > f.length()) return null;
        byte[] raw = new byte[(int) csize];
        f.seek(off);
        f.readFully(raw);
        Inflater z = new Inflater();
        try {
            z.setInput(raw);
            byte[] out = new byte[(int) size];
            int got = 0;
            while (got < out.length && !z.finished()) {
                int k = z.inflate(out, got, out.length - got);
                if (k == 0 && (z.needsInput() || z.needsDictionary())) break;
                got += k;
            }
            return got == out.length ? out : null;
        } catch (DataFormatException e) {
            return csize == size ? raw : null;
        } finally {
            z.end();
        }
    }

    // ---- the file

    /** The faces of an "SBC" collision file that face up (a floor, a slope), from above; null if not understood. */
    static Mesh faces(byte[] d) {
        if (d.length < 0x40 || d[0] != 'S' || d[1] != 'B' || d[2] != 'C' || (d[3] & 0xff) != 0xff) return null;
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int nt = b.getInt(0x18), nv = b.getInt(0x1c);
        if (nt < 1 || nv < 3 || nt > 400000 || nv > 400000) return null;
        long trisLen = 32L * nt;
        int first = -1;
        for (int o = 0x30; o + trisLen + 12 + 16L * nv <= d.length; o += 4) {
            if (!triangle(b, o, nv)) continue;
            int k = 1;
            while (k < nt && triangle(b, o + 32 * k, nv)) k++;
            if (k == nt) {
                first = o;
                break;
            }
            o += 32 * (k - 1);  // (not the run: on past what was checked)
        }
        if (first < 0) return null;
        int v0 = (int) (first + trisLen + 12);
        float bx0 = b.getFloat(0x30) + 1, bz0 = b.getFloat(0x38) + 1, bx1 = b.getFloat(0x40) - 1, bz1 = b.getFloat(0x48) - 1;
        float[] xz = new float[nt * 6], ys = new float[nt];
        int[] corner = new int[nt * 3], parent = new int[nv];
        for (int i = 0; i < nv; i++) parent[i] = i;
        int n = 0;
        for (int i = 0; i < nt; i++) {
            int o = first + 32 * i;
            if (b.getFloat(o + 0x10) < 0.5f) continue;  // (a wall or a ceiling)
            boolean edge = false;
            float y = 0;
            for (int k = 0; k < 3; k++) {
                int idx = b.getShort(o + 0x18 + 2 * k) & 0xffff, v = v0 + 16 * idx;
                float x = b.getFloat(v), z = b.getFloat(v + 8);
                edge |= x <= bx0 || x >= bx1 || z <= bz0 || z >= bz1;
                xz[n * 6 + 2 * k] = x;
                xz[n * 6 + 2 * k + 1] = z;
                corner[n * 3 + k] = idx;
                y += b.getFloat(v + 4) / 3;
            }
            if (edge) continue;
            ys[n] = y;
            join(parent, corner[n * 3], corner[n * 3 + 1]);
            join(parent, corner[n * 3 + 1], corner[n * 3 + 2]);
            n++;
        }
        int[] comp = new int[n], number = new int[nv];
        java.util.Arrays.fill(number, -1);
        int pieces = 0;
        for (int i = 0; i < n; i++) {
            int root = find(parent, corner[i * 3]);
            if (number[root] < 0) number[root] = pieces++;
            comp[i] = number[root];
        }
        return new Mesh(java.util.Arrays.copyOf(xz, n * 6), java.util.Arrays.copyOf(ys, n), comp, pieces);
    }

    private static int find(int[] p, int a) {
        while (p[a] != a) {
            p[a] = p[p[a]];
            a = p[a];
        }
        return a;
    }

    private static void join(int[] p, int a, int b) {
        a = find(p, a);
        b = find(p, b);
        if (a != b) p[a] = b;
    }

    // Does a triangle record start at o: three different vertices that exist, a unit normal
    private static boolean triangle(ByteBuffer b, int o, int nv) {
        if (o < 0 || o + 32 > b.limit()) return false;
        int p = b.getShort(o + 0x18) & 0xffff, q = b.getShort(o + 0x1a) & 0xffff, r = b.getShort(o + 0x1c) & 0xffff;
        if (p >= nv || q >= nv || r >= nv || p == q || q == r || p == r) return false;
        float x = b.getFloat(o + 0x0c), y = b.getFloat(o + 0x10), z = b.getFloat(o + 0x14), len = x * x + y * y + z * z;
        return len > 0.98f && len < 1.02f;
    }
}
