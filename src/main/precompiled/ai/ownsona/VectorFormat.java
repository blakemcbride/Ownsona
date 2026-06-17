package ai.ownsona;

/**
 * Format a float[] embedding as a pgvector text literal.
 *
 * <p>pgvector accepts embeddings as a string of the form {@code [v1,v2,...]}
 * cast to {@code vector}, e.g. {@code '[0.1,0.2]'::vector}.  We bind the
 * literal as a JDBC text parameter and add the {@code ::vector} cast in the
 * SQL itself, since Kiss's prepared-statement helpers don't know about
 * pgvector's custom type.
 */
public final class VectorFormat {

    public static String toLiteral(float[] vec) {
        if (vec == null)
            throw new IllegalArgumentException("vec is null");
        final StringBuilder sb = new StringBuilder(vec.length * 12 + 2);
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0)
                sb.append(',');
            sb.append(vec[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Format a string array as a Postgres array literal: {@code {"a","b"}} with
     * embedded backslashes and double-quotes escaped.  Bind with {@code ?::text[]}.
     */
    public static String toPgArrayLiteral(String[] tags) {
        if (tags == null || tags.length == 0)
            return "{}";
        final StringBuilder sb = new StringBuilder();
        sb.append('{');
        for (int i = 0; i < tags.length; i++) {
            if (i > 0)
                sb.append(',');
            sb.append('"');
            for (int j = 0; j < tags[i].length(); j++) {
                final char c = tags[i].charAt(j);
                if (c == '\\' || c == '"')
                    sb.append('\\');
                sb.append(c);
            }
            sb.append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * Parse a pgvector text literal ({@code [v1,v2,...]}) back into a
     * float[].  Returns null for null/empty input.  Used to read a stored
     * vector column (selected as {@code ::text}) for in-Java updates ---
     * cheaper and more portable than relying on pgvector arithmetic
     * operators, which vary across versions.
     */
    public static float[] parseLiteral(String s) {
        if (s == null)
            return null;
        String t = s.trim();
        if (t.isEmpty())
            return null;
        if (t.charAt(0) == '[')
            t = t.substring(1);
        if (t.endsWith("]"))
            t = t.substring(0, t.length() - 1);
        t = t.trim();
        if (t.isEmpty())
            return new float[0];
        final String[] parts = t.split(",");
        final float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++)
            out[i] = Float.parseFloat(parts[i].trim());
        return out;
    }

    /**
     * Reward-weighted move of {@code base} toward {@code toward}:
     * {@code (1-w)*base + w*toward}, element-wise.  When {@code base} is
     * null this is the first observation, so a copy of {@code toward} is
     * returned.  Used to update a memory's learned context centroid on
     * reinforcement.  No normalization --- cosine ranking is scale-invariant.
     */
    public static float[] blend(float[] base, float[] toward, double towardWeight) {
        if (toward == null)
            throw new IllegalArgumentException("toward is null");
        if (base == null)
            return toward.clone();
        if (base.length != toward.length)
            throw new IllegalArgumentException(
                    "vector length mismatch: " + base.length + " vs " + toward.length);
        final double bw = towardWeight;
        final double aw = 1.0 - towardWeight;
        final float[] out = new float[base.length];
        for (int i = 0; i < base.length; i++)
            out[i] = (float) (aw * base[i] + bw * toward[i]);
        return out;
    }

    private VectorFormat() {
    }
}
