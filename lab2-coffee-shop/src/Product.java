import java.util.Locale;

/** The three products of the shop and their preparation times. */
public enum Product {
    COFFEE("coffee", 4),
    TEA("tea", 3),
    CROISSANT("croissant", 2);

    public final String label;
    public final int seconds;

    Product(String label, int seconds) {
        this.label = label;
        this.seconds = seconds;
    }

    /** "coffee", "Coffee", "coffees" -> COFFEE; null if the name is unknown. */
    public static Product parse(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (Product p : values()) {
            if (n.equals(p.label) || n.equals(p.label + "s")) return p;
        }
        return null;
    }
}
