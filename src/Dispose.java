import java.util.Locale;

public class Dispose extends Element {
    private double serviceSum = 0.0;
    private double serviceSumSq = 0.0;
    private double serviceMin = Double.POSITIVE_INFINITY;
    private double serviceMax = Double.NEGATIVE_INFINITY;

    private double waitSum = 0.0;
    private double waitSumSq = 0.0;
    private double waitMin = Double.POSITIVE_INFINITY;
    private double waitMax = Double.NEGATIVE_INFINITY;

    public Dispose(String name, FunRand rand) {
        super(name, rand);
    }

    @Override
    public void inAct(Job job) {
        quantity++;
        double serviceTime = tCurr - job.getTimeIn();
        double waitTime = serviceTime - job.getExcludedWaitTime();

        serviceSum += serviceTime;
        serviceSumSq += serviceTime * serviceTime;
        if (serviceTime < serviceMin) serviceMin = serviceTime;
        if (serviceTime > serviceMax) serviceMax = serviceTime;

        waitSum += waitTime;
        waitSumSq += waitTime * waitTime;
        if (waitTime < waitMin) waitMin = waitTime;
        if (waitTime > waitMax) waitMax = waitTime;

        log(String.format(Locale.US, "Вихід %s (T_обсл=%.3f, T_очік=%.3f)", job, serviceTime, waitTime));
    }

    @Override
    public void resetStatistics() {
        super.resetStatistics();
        serviceSum = 0.0;
        serviceSumSq = 0.0;
        serviceMin = Double.POSITIVE_INFINITY;
        serviceMax = Double.NEGATIVE_INFINITY;
        waitSum = 0.0;
        waitSumSq = 0.0;
        waitMin = Double.POSITIVE_INFINITY;
        waitMax = Double.NEGATIVE_INFINITY;
    }

    public double getServiceSum() { return serviceSum; }
    public double getServiceSumSq() { return serviceSumSq; }
    public double getServiceMin() { return serviceMin; }
    public double getServiceMax() { return serviceMax; }

    public double getWaitSum() { return waitSum; }
    public double getWaitSumSq() { return waitSumSq; }
    public double getWaitMin() { return waitMin; }
    public double getWaitMax() { return waitMax; }
}