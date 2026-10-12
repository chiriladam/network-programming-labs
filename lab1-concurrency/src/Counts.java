/** The result of every task: how many negative, positive and zero numbers were seen. */
public final class Counts {
    public long negative;
    public long positive;
    public long zero;

    public Counts() { }

    public Counts(long negative, long positive, long zero) {
        this.negative = negative;
        this.positive = positive;
        this.zero = zero;
    }

    /** Classifies one number. */
    public void add(int value) {
        if (value < 0) negative++;
        else if (value > 0) positive++;
        else zero++;
    }

    public void add(Counts other) {
        negative += other.negative;
        positive += other.positive;
        zero += other.zero;
    }

    public long total() {
        return negative + positive + zero;
    }

    public boolean sameAs(Counts other) {
        return other != null
                && negative == other.negative
                && positive == other.positive
                && zero == other.zero;
    }

    @Override
    public String toString() {
        return String.format("negative=%,d  positive=%,d  zero=%,d  total=%,d",
                negative, positive, zero, total());
    }
}
