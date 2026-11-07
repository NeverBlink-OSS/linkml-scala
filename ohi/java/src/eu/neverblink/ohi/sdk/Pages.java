package eu.neverblink.ohi.sdk;

import java.nio.charset.StandardCharsets;

/** Links to pages on ohi.dev. */
public final class Pages {
    private Pages() {
    }

    /** The page of one release on ohi.dev, such as {@code https://ohi.dev/o/w3c/sosa@2017}. */
    public static String ohiReleasePage(String namespace, String name, String version) {
        return "https://ohi.dev/o/" + uriComponent(namespace) + "/" + uriComponent(name) + "@"
                + uriComponent(version);
    }

    public static String ohiReleasePage(Publication publication) {
        return ohiReleasePage(publication.getNamespace(), publication.getName(), publication.getVersion());
    }

    public static String ohiReleasePage(SearchHit hit) {
        return ohiReleasePage(hit.getNamespace(), hit.getName(), hit.getVersion());
    }

    /** Percent-encodes a path segment, leaving the same characters as JavaScript's encodeURIComponent. */
    static String uriComponent(String value) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                    || "-_.!~*'()".indexOf(c) >= 0) {
                sb.append(c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }
}
