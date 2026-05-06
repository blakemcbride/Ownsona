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

    private VectorFormat() {
    }
}
