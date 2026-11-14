package com.nexodb.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Bounded stream reads work on Android 8, including older core libraries. */
public final class StreamCompat {
    private StreamCompat() {}
    public static byte[] readNBytes(InputStream input, int limit) throws IOException {
        if (limit < 0) throw new IllegalArgumentException("Negative read limit");
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[Math.min(Math.max(limit, 1), 8192)];
        int remaining = limit;
        while (remaining > 0) {
            int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (count < 0) break;
            if (count == 0) {
                int value = input.read();
                if (value < 0) break;
                output.write(value); remaining--;
            } else {
                output.write(buffer, 0, count); remaining -= count;
            }
        }
        return output.toByteArray();
    }
}
