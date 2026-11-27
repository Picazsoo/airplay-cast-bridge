package io.github.picazsoo.airplaybridge;

import java.nio.charset.StandardCharsets;

/**
 * Minimal QR code encoder: byte mode, error correction level L, versions 1-4 (up to 78 bytes),
 * mask 0. Enough for a short URL on the TV screen without a library. Follows the structure of
 * Nayuki's reference implementation (QR Code Model 2, ISO/IEC 18004).
 */
final class Qr {
    private static final int[] DATA_CW = {0, 19, 34, 55, 80};   // per version, level L
    private static final int[] EC_CW = {0, 7, 10, 15, 20};      // single block for v1-4 L

    final int size;
    private final boolean[][] mod, fn;

    private Qr(int version) {
        size = 17 + 4 * version;
        mod = new boolean[size][size];
        fn = new boolean[size][size];
    }

    boolean get(int x, int y) { return mod[y][x]; }

    static Qr encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int v = 1;
        while (v <= 4 && 4 + 8 + 8 * data.length > DATA_CW[v] * 8) v++;
        if (v > 4) throw new IllegalArgumentException("text too long for QR v4-L");
        Qr q = new Qr(v);

        // data bits: mode 0100 (byte), 8-bit count, bytes, terminator, pad
        int cap = DATA_CW[v] * 8;
        boolean[] bits = new boolean[cap];
        int n = 0;
        n = put(bits, n, 0x4, 4);
        n = put(bits, n, data.length, 8);
        for (byte b : data) n = put(bits, n, b & 0xFF, 8);
        n = Math.min(cap, n + 4);                 // terminator (zeros)
        n = (n + 7) / 8 * 8;                      // to byte boundary
        byte[] cw = new byte[DATA_CW[v] + EC_CW[v]];
        for (int i = 0; i < n / 8; i++) {
            int b = 0;
            for (int j = 0; j < 8; j++) b = (b << 1) | (bits[i * 8 + j] ? 1 : 0);
            cw[i] = (byte) b;
        }
        for (int i = n / 8, k = 0; i < DATA_CW[v]; i++, k++) cw[i] = (byte) (k % 2 == 0 ? 0xEC : 0x11);
        byte[] ec = rsRemainder(cw, DATA_CW[v], rsDivisor(EC_CW[v]));
        System.arraycopy(ec, 0, cw, DATA_CW[v], ec.length);

        q.drawFunctionPatterns(v);
        q.drawCodewords(cw);
        q.applyMask0();
        q.drawFormatBits();
        return q;
    }

    private static int put(boolean[] bits, int n, int val, int len) {
        for (int i = len - 1; i >= 0; i--) bits[n++] = ((val >>> i) & 1) != 0;
        return n;
    }

    private void set(int x, int y, boolean dark) { mod[y][x] = dark; fn[y][x] = true; }

    private void drawFunctionPatterns(int v) {
        for (int i = 0; i < size; i++) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0); }
        finder(3, 3); finder(size - 4, 3); finder(3, size - 4);
        if (v >= 2) { int p = 4 * v + 10; alignment(p, p); }
        // reserve format areas (real bits drawn after masking)
        for (int i = 0; i < 9; i++) { if (!fn[i][8]) set(8, i, false); if (!fn[8][i]) set(i, 8, false); }
        for (int i = 0; i < 8; i++) { set(size - 1 - i, 8, false); set(8, size - 1 - i, false); }
        set(8, size - 8, true);                  // dark module
    }

    private void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++)
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= size || y >= size) continue;
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                set(x, y, d != 2 && d != 4);
            }
    }

    private void alignment(int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++)
            for (int dx = -2; dx <= 2; dx++)
                set(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
    }

    private void drawCodewords(byte[] cw) {
        int i = 0, total = cw.length * 8;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++)
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean up = ((right + 1) & 2) == 0;
                    int y = up ? size - 1 - vert : vert;
                    if (!fn[y][x] && i < total) {
                        mod[y][x] = ((cw[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                        i++;
                    }
                }
        }
    }

    private void applyMask0() {
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++)
                if (!fn[y][x] && (x + y) % 2 == 0) mod[y][x] = !mod[y][x];
    }

    private void drawFormatBits() {
        int data = (1 << 3) | 0;                 // level L (01), mask 0
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    private static boolean bit(int x, int i) { return ((x >>> i) & 1) != 0; }

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    private static int[] rsDivisor(int degree) {
        int[] r = new int[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                r[j] = mul(r[j], root);
                if (j + 1 < degree) r[j] ^= r[j + 1];
            }
            root = mul(root, 0x02);
        }
        return r;
    }

    private static byte[] rsRemainder(byte[] cw, int len, int[] div) {
        int[] r = new int[div.length];
        for (int k = 0; k < len; k++) {
            int factor = (cw[k] & 0xFF) ^ r[0];
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= mul(div[i], factor);
        }
        byte[] out = new byte[r.length];
        for (int i = 0; i < r.length; i++) out[i] = (byte) r[i];
        return out;
    }

    /** Render with a 4-module quiet zone, black on white. */
    android.graphics.Bitmap toBitmap(int scale) {
        int q = 4, n = (size + 2 * q) * scale;
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888);
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++) {
                int mx = x / scale - q, my = y / scale - q;
                boolean dark = mx >= 0 && my >= 0 && mx < size && my < size && mod[my][mx];
                b.setPixel(x, y, dark ? 0xFF000000 : 0xFFFFFFFF);
            }
        return b;
    }
}
