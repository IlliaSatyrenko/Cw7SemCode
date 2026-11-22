import java.util.Random;

public class FunRand {
    private final Random rng;

    public FunRand() {
        this.rng = new Random();
    }

    public FunRand(long seed) {
        this.rng = new Random(seed);
    }

    public double exp(double timeMean) {
        double a = 0.0;
        while (a == 0.0) {
            a = rng.nextDouble();
        }
        return -timeMean * Math.log(a);
    }

    // Рівномірний розподіл unif(a, delta) на інтервалі [a - delta; a + delta]
    public double unif(double mean, double halfWidth) {
        return (mean - halfWidth) + 2.0 * halfWidth * rng.nextDouble();
    }

    // Нормальний розподіл norm(m, sigma) із контролем додатності часу
    public double normPositive(double mean, double stdDev) {
        double value;
        do {
            value = mean + stdDev * rng.nextGaussian();
        } while (value <= 0.0);
        return value;
    }

    public double nextDouble() {
        return rng.nextDouble();
    }
}