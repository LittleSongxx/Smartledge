package org.smartledge.ai.manage.support;

import java.util.ArrayList;
import java.util.List;

/** Length control with source offsets, preferring paragraph, line and sentence boundaries. */
public final class SourceTextSplitter {
    private SourceTextSplitter() { }
    public record Fragment(String text, int start, int end) { }

    public static List<Fragment> split(String source, int maxChars, int overlap) {
        if (maxChars < 1 || overlap < 0 || overlap >= maxChars) {
            throw new IllegalArgumentException("Invalid split budget");
        }
        List<Fragment> fragments = new ArrayList<>();
        int start = 0;
        while (start < source.length()) {
            while (start < source.length() && Character.isWhitespace(source.charAt(start))) { start++; }
            if (start == source.length()) { break; }
            int end = Math.min(source.length(), start + maxChars);
            if (end < source.length()) {
                if (Character.isLowSurrogate(source.charAt(end))) { end--; }
                int preferred = boundary(source, start, end);
                if (preferred > start + overlap) { end = preferred; }
            }
            if (end <= start) { throw new ChunkingProfileException("PROFILE_UNIT_TOO_LARGE", null); }
            int trimmedEnd = end;
            while (trimmedEnd > start && Character.isWhitespace(source.charAt(trimmedEnd - 1))) { trimmedEnd--; }
            if (trimmedEnd > start) {
                fragments.add(new Fragment(source.substring(start, trimmedEnd), start, trimmedEnd));
            }
            if (end == source.length()) { break; }
            int next = Math.max(start + 1, end - overlap);
            if (next < source.length() && Character.isLowSurrogate(source.charAt(next))) { next++; }
            start = next;
        }
        return List.copyOf(fragments);
    }

    private static int boundary(String source, int start, int end) {
        for (String separator : List.of("\n\n", "\n", ". ", "! ", "? ", "\u3002", "\uff01", "\uff1f", " ")) {
            int offset = source.lastIndexOf(separator, end - separator.length());
            if (offset >= start) { return offset + separator.length(); }
        }
        return end;
    }
}
