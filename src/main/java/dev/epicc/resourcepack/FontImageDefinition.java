package dev.epicc.resourcepack;

/** A configured bitmap glyph that can be inserted into Adventure text. */
public record FontImageDefinition(
        String id,
        String texture,
        int codepoint,
        int scale,
        int yPosition
) {

    public String glyph() {
        return Character.toString((char) codepoint);
    }
}
