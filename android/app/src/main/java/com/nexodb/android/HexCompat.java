package com.nexodb.android;

/** Java 17 hex API equivalent on Android 8+, without modifying the shared desktop sources. */
public final class HexCompat {
    private static final HexCompat INSTANCE=new HexCompat();
    public static HexCompat of(){return INSTANCE;}
    public String formatHex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);for(byte value:bytes){int b=value&255;out.append("0123456789abcdef".charAt(b>>>4)).append("0123456789abcdef".charAt(b&15));}return out.toString();}
}
