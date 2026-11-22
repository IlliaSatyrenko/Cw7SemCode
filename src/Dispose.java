import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class Dispose extends Element {
    private final List<Double> serviceTimes = new ArrayList<>();
    private final List<Double> waitTimes = new ArrayList<>();

    public Dispose(String name, FunRand rand) {
        super(name, rand);
    }

    @Override
    public void inAct(Job job) {
        quantity++;
        double serviceTime = tCurr - job.getTimeIn();
        double waitTime = serviceTime - job.getExcludedWaitTime();
        serviceTimes.add(serviceTime);
        waitTimes.add(waitTime);
        log(String.format(Locale.US, "Вихід %s (T_обсл=%.3f, T_очік=%.3f)", job, serviceTime, waitTime));
    }

    @Override
    public void resetStatistics() {
        super.resetStatistics();
        serviceTimes.clear();
        waitTimes.clear();
    }

    public List<Double> getServiceTimes() {
        return Collections.unmodifiableList(serviceTimes);
    }

    public List<Double> getWaitTimes() {
        return Collections.unmodifiableList(waitTimes);
    }
}