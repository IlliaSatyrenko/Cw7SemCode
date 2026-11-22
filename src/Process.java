import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

public class Process extends Element {
    public enum RoutingMode {
        PRIORITY,
        PROBABILITY
    }

    private final Function<Job, Double> delayFunction; // null => миттєвий вузол (t = 0)
    private final Deque<Job> queue = new ArrayDeque<>();
    private final int maxQueue;
    private final List<Route> routes = new ArrayList<>();

    private RoutingMode routingMode = RoutingMode.PRIORITY;
    private boolean excludeDelayFromWait = false;
    private Job currentJob = null;

    private double meanQueue = 0.0;
    private double workTime = 0.0;
    private int failure = 0;

    public Process(String name, int maxQueue, Function<Job, Double> delayFunction, FunRand rand) {
        super(name, rand);
        this.maxQueue = maxQueue;
        this.delayFunction = delayFunction;
    }

    public void addRoute(Route route) {
        routes.add(route);
    }

    public void setRoutingMode(RoutingMode routingMode) {
        this.routingMode = routingMode;
    }

    public void setExcludeDelayFromWait(boolean excludeDelayFromWait) {
        this.excludeDelayFromWait = excludeDelayFromWait;
    }

    @Override
    public void inAct(Job job) {
        // Миттєвий вузол (t = 0): A, F_i, G_i, U_i, V_i
        if (delayFunction == null) {
            if (maxQueue == 0) {
                state = 1;
                currentJob = job;
                Route route = selectRoute();
                state = 0;
                currentJob = null;
                quantity++;
                if (route != null) {
                    deliver(route, job);
                }
            } else {
                if (queue.size() < maxQueue) {
                    queue.add(job);
                } else {
                    failure++;
                }
            }
            return;
        }

        // Пристрій із часовою затримкою: W_i, T_i, R_i, C_i
        if (state == 0) {
            startService(job);
        } else if (queue.size() < maxQueue) {
            queue.add(job);
        } else {
            failure++;
        }
    }

    private void startService(Job job) {
        state = 1;
        currentJob = job;
        double delay = delayFunction.apply(job);
        if (excludeDelayFromWait) {
            job.addExcludedWaitTime(delay);
        }
        tNext = tCurr + delay;
        log(String.format(Locale.US, "Старт %s (dt=%.3f, кінець о t=%.3f)", job, delay, tNext));
    }

    @Override
    public void outAct() {
        super.outAct();
        Job finished = currentJob;
        state = 0;
        currentJob = null;
        tNext = Double.MAX_VALUE;
        log("Завершення " + finished);

        Route route = selectRoute();
        if (route != null && finished != null) {
            deliver(route, finished);
        }
        if (!queue.isEmpty()) {
            startService(queue.poll());
        }
    }

    // Каскадне зняття блокувань для миттєвих вузлів із чергою (Q-A, P_i-G_i, L_i-U_i)
    @Override
    public boolean tryUnblock() {
        if (delayFunction != null || maxQueue == 0 || queue.isEmpty()) {
            return false;
        }
        Route route = selectRoute();
        if (route == null) {
            return false;
        }

        if (route.isGroupBatch()) {
            int k = Math.min(queue.size(), route.getMaxBatchSize());
            List<Job> items = new ArrayList<>(k);
            for (int i = 0; i < k; i++) {
                items.add(queue.poll());
            }
            Job batch = Job.createBatch(items);
            quantity++;
            log("Групування k=" + k + " -> " + route.getTarget().getName() + " : " + batch);
            deliver(route, batch);
        } else {
            Job job = queue.poll();
            quantity++;
            log("Передача " + job + " -> " + route.getTarget().getName());
            deliver(route, job);
        }
        return true;
    }

    private Route selectRoute() {
        if (routes.isEmpty()) {
            return null;
        }
        if (routingMode == RoutingMode.PROBABILITY) {
            double u = rand.nextDouble();
            double cumulative = 0.0;
            Route lastPositive = null;
            for (Route r : routes) {
                if (r.getProbability() <= 0.0 || r.isBlocked()) {
                    continue;
                }
                lastPositive = r;
                cumulative += r.getProbability();
                if (u < cumulative) {
                    return r;
                }
            }
            return lastPositive;
        }

        Route best = null;
        for (Route r : routes) {
            if (!r.isBlocked()) {
                if (best == null || r.getPriority() > best.getPriority()) {
                    best = r;
                }
            }
        }
        return best;
    }

    private void deliver(Route route, Job job) {
        if (route.isUngroupBatch()) {
            List<Job> unpacked = job.unpackSortedFifo();
            log("Розгрупування 1/" + unpacked.size() + " (FIFO) -> " + route.getTarget().getName() + " : " + unpacked);
            for (Job sub : unpacked) {
                route.getTarget().inAct(sub);
            }
        } else {
            route.getTarget().inAct(job);
        }
    }

    @Override
    public void doStatistics(double delta) {
        meanQueue += queue.size() * delta;
        workTime += state * delta;
    }

    @Override
    public void resetStatistics() {
        super.resetStatistics();
        meanQueue = 0.0;
        workTime = 0.0;
        failure = 0;
    }

    @Override
    public int getTotalInside() {
        return state + queue.size();
    }

    public int getQueueSize() {
        return queue.size();
    }

    public double getMeanQueue() {
        return meanQueue;
    }

    public double getWorkTime() {
        return workTime;
    }

    public int getFailure() {
        return failure;
    }
}