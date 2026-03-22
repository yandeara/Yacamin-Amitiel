package br.com.yacamin.amitiel.application.service.util;

public final class SlugUtils {

    private SlugUtils() {}

    /**
     * Extrai o market group prefix de um slug.
     * Ex: "btc-updown-5m-1711000200" → "btc-updown-5m-"
     */
    public static String extractMarketGroup(String slug) {
        if (slug == null) return "";
        int lastDash = slug.lastIndexOf('-');
        if (lastDash < 0) return "";
        return slug.substring(0, lastDash + 1);
    }

    /**
     * Extrai o unix timestamp do final do slug.
     * Ex: "btc-updown-5m-1711000200" → 1711000200
     */
    public static long extractUnixFromSlug(String slug) {
        if (slug == null) return 0;
        int lastDash = slug.lastIndexOf('-');
        if (lastDash < 0) return 0;
        try {
            return Long.parseLong(slug.substring(lastDash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
