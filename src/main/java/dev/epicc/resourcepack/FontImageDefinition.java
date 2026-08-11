package dev.epicc.resourcepack;

/** A configured bitmap glyph that can be inserted into Adventure text. */
public record FontImageDefinition(
        String id,
        String texture,
        int codepoint,
        int scale,
        int yPosition,
        int xOffset,
        int xOffsetCodepoint
) {

    public String glyph() {
        return Character.toString((char) codepoint);
    }

    public boolean hasXOffset() {
        return xOffset != 0;
    }

    public String xOffsetGlyph() {
        return Character.toString((char) xOffsetCodepoint);
    }

    public FontImageDefinition withXOffsetCodepoint(int codepoint) {
        return new FontImageDefinition(id, texture, this.codepoint, scale, yPosition, xOffset, codepoint);
    }
}
