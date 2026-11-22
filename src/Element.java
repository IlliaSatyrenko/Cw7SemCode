import java.util.Locale;

public abstract class Element {
    protected final String name;
    protected final FunRand rand;
    protected double tCurr = 0.0;
    protected double tNext = Double.MAX_VALUE;
    protected int state = 0;
    protected int quantity = 0;
    protected boolean traceEnabled = false;

    protected Element(String name, FunRand rand) {
        this.name = name;
        this.rand = rand;
    }

    public void inAct(Job job) {
        // Перевизначається у підкласах
    }

    public void outAct() {
        quantity++;
    }

    public void doStatistics(double delta) {
        // Перевизначається у Process
    }

    public void resetStatistics() {
        quantity = 0;
    }

    public boolean tryUnblock() {
        return false;
    }

    public int getTotalInside() {
        return state;
    }

    public String getName() {
        return name;
    }

    public double getTCurr() {
        return tCurr;
    }

    public void setTCurr(double tCurr) {
        this.tCurr = tCurr;
    }

    public double getTNext() {
        return tNext;
    }

    public void setTNext(double tNext) {
        this.tNext = tNext;
    }

    public int getState() {
        return state;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setTraceEnabled(boolean traceEnabled) {
        this.traceEnabled = traceEnabled;
    }

    protected void log(String message) {
        if (traceEnabled) {
            System.out.printf(Locale.US, "[t=%7.3f] %-6s | %s%n", tCurr, name, message);
        }
    }
}