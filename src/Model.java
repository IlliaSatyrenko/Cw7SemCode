import java.util.List;

public class Model {
    private final List<Element> list;
    private double tCurr = 0.0;
    private double tNext = 0.0;
    private double observedTime = 0.0;

    public Model(List<Element> elements) {
        this.list = elements;
    }

    public void setTraceEnabled(boolean enabled) {
        for (Element e : list) {
            e.setTraceEnabled(enabled);
        }
    }

    public void simulate(double modelingTime) {
        simulate(modelingTime, 0.0);
    }

    public void simulate(double modelingTime, double warmupTime) {
        boolean warmupDone = (warmupTime <= 0.0);
        observedTime = 0.0;

        while (tCurr < modelingTime) {
            tNext = Double.MAX_VALUE;
            for (Element e : list) {
                if (e.getTNext() < tNext) {
                    tNext = e.getTNext();
                }
            }

            if (!warmupDone && tCurr < warmupTime && tNext > warmupTime) {
                tNext = warmupTime;
            }
            if (tNext > modelingTime) {
                tNext = modelingTime;
            }

            double delta = tNext - tCurr;
            if (warmupDone && delta > 0.0) {
                for (Element e : list) {
                    e.doStatistics(delta);
                }
                observedTime += delta;
            }

            tCurr = tNext;
            for (Element e : list) {
                e.setTCurr(tCurr);
            }

            if (!warmupDone && tCurr >= warmupTime) {
                for (Element e : list) {
                    e.resetStatistics();
                }
                warmupDone = true;
            }

            if (tCurr >= modelingTime) {
                break;
            }

            // Пріоритет завершень обслуговування (Process) над новими надходженнями (Create)
            for (Element e : list) {
                if (e instanceof Process && e.getTNext() == tCurr) {
                    e.outAct();
                }
            }
            for (Element e : list) {
                if (e instanceof Create && e.getTNext() == tCurr) {
                    e.outAct();
                }
            }

            // Каскадна стабілізація миттєвих вузлів (t = 0)
            boolean changed;
            do {
                changed = false;
                for (Element e : list) {
                    if (e.tryUnblock()) {
                        changed = true;
                    }
                }
            } while (changed);
        }
    }

    public double getObservedTime() {
        return observedTime;
    }
}