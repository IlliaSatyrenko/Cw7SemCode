import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;

public class SimModel {

    public record Config(
            double meanArrival,
            double roadMean,
            double roadHalfWidth,
            double searchMeanPerItem,
            double searchCv,
            double checkoutMean,
            double checkoutHalfWidth,
            double waitTimer,
            double pWrongItem,
            double pOutOfStock,
            double pReplacement,
            int clerksCount,
            int maxBatchSize
    ) {
        public static Config defaults(int variant) {
            double wait = (variant == 1) ? 0.0 : 1.0;
            double pWrong = (variant == 3) ? 0.15 : 0.0;
            double pOut = (variant == 3) ? 0.10 : 0.0;
            double pRepl = (variant == 3) ? 0.25 : 0.0;
            return new Config(2.0, 1.0, 0.5,3.0,
                    0.2, 2.0, 1.0, wait,
                    pWrong, pOut, pRepl, 3, 6);
        }

        public double pPaid() {
            return (1.0 - pOutOfStock) * (1.0 - pWrongItem);
        }

        public double pRepeat() {
            return pOutOfStock * pReplacement + (1.0 - pOutOfStock) * pWrongItem;
        }

        public double pLeave() {
            return pOutOfStock * (1.0 - pReplacement);
        }

        public Config withMeanArrival(double v) {
            return new Config(v, roadMean, roadHalfWidth, searchMeanPerItem, searchCv,
                    checkoutMean, checkoutHalfWidth, waitTimer, pWrongItem, pOutOfStock, pReplacement, clerksCount, maxBatchSize);
        }

        public Config withSearchMeanPerItem(double v) {
            return new Config(meanArrival, roadMean, roadHalfWidth, v, searchCv,
                    checkoutMean, checkoutHalfWidth, waitTimer, pWrongItem, pOutOfStock, pReplacement, clerksCount, maxBatchSize);
        }

        public Config withCheckoutMean(double v) {
            return new Config(meanArrival, roadMean, roadHalfWidth, searchMeanPerItem, searchCv,
                    v, checkoutHalfWidth, waitTimer, pWrongItem, pOutOfStock, pReplacement, clerksCount, maxBatchSize);
        }

        public Config withWaitTimer(double v) {
            return new Config(meanArrival, roadMean, roadHalfWidth, searchMeanPerItem, searchCv,
                    checkoutMean, checkoutHalfWidth, v, pWrongItem, pOutOfStock, pReplacement, clerksCount, maxBatchSize);
        }

        public Config withIssues(double wrong, double out, double repl) {
            return new Config(meanArrival, roadMean, roadHalfWidth, searchMeanPerItem, searchCv,
                    checkoutMean, checkoutHalfWidth, waitTimer, wrong, out, repl, clerksCount, maxBatchSize);
        }
    }

    public record SampleStats(int count, double mean, double std, double min, double max) {
        public static SampleStats fromSums(int count, double sum, double sumSq, double min, double max) {
            if (count == 0) {
                return new SampleStats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            }
            double mean = sum / count;
            double variance = (count > 1)
                    ? Math.max(0.0, (sumSq - count * mean * mean) / (count - 1))
                    : 0.0;
            return new SampleStats(count, mean, Math.sqrt(variance), min, max);
        }
    }

    public record Result(
            double uClerk,
            SampleStats tService,
            SampleStats tWait,
            double kMean,
            double lMean,
            int nPaid,
            int nLeft,
            int nTrips,
            int failures
    ) {
        public String toTableRow(String label) {
            String tObsl = tService.count() > 0
                    ? String.format(Locale.US, "%6.2f ± %-5.2f", tService.mean(), tService.std())
                    : "      N/A     ";
            String tOchik = tWait.count() > 0
                    ? String.format(Locale.US, "%6.2f ± %-5.2f", tWait.mean(), tWait.std())
                    : "      N/A     ";
            return String.format(Locale.US,
                    "%-34s | %6.2f%% | %s | %s | %5.3f | %6.3f | %5d | %4d | %5d | %d",
                    label, uClerk * 100.0, tObsl, tOchik, kMean, lMean, nPaid, nLeft, nTrips, failures);
        }
    }

    public static Result runModel(int variant, Config cfg, double modelingTime,
                                  double warmupTime, long seed, boolean trace) {
        FunRand rand = new FunRand(seed);
        int m = cfg.clerksCount();

        Create create = new Create("Вхід", cfg.meanArrival(), rand);
        Process nodeA = new Process("A", Integer.MAX_VALUE, null, rand);
        create.setNextElement(nodeA);

        Function<Job, Double> tripDelay = job -> {
            int k = job.getBatchSize();
            double searchMean = cfg.searchMeanPerItem() * k;
            return rand.unif(cfg.roadMean(), cfg.roadHalfWidth())
                    + rand.normPositive(searchMean, cfg.searchCv() * searchMean)
                    + rand.unif(cfg.roadMean(), cfg.roadHalfWidth());
        };
        Function<Job, Double> repeatDelay = job ->
                rand.unif(cfg.roadMean(), cfg.roadHalfWidth())
                        + rand.normPositive(cfg.searchMeanPerItem(), cfg.searchCv() * cfg.searchMeanPerItem())
                        + rand.unif(cfg.roadMean(), cfg.roadHalfWidth());
        Function<Job, Double> checkoutDelay = job -> rand.unif(cfg.checkoutMean(), cfg.checkoutHalfWidth());

        Process[] f = new Process[m];
        Process[] w = new Process[m];
        Process[] g = new Process[m];
        Process[] t = new Process[m];
        Process[] u = new Process[m];
        Process[] v = new Process[m];
        Process[] r = new Process[m];
        Process[] c = new Process[m];
        Dispose[] nOut = new Dispose[m];
        Dispose[] zOut = new Dispose[m];

        for (int i = 0; i < m; i++) {
            int num = i + 1;
            t[i] = new Process("T" + num, 0, tripDelay, rand);
            nOut[i] = new Dispose("N" + num, rand);

            if (variant == 1) {
                c[i] = new Process("C" + num, cfg.maxBatchSize(), checkoutDelay, rand);
                c[i].setExcludeDelayFromWait(true);
                t[i].addRoute(Route.ungroup(c[i]));
                c[i].addRoute(Route.direct(nOut[i]));
            } else {
                f[i] = new Process("F" + num, 0, null, rand);
                w[i] = new Process("W" + num, 0, job -> cfg.waitTimer(), rand);
                g[i] = new Process("G" + num, cfg.maxBatchSize(), null, rand);
                w[i].addRoute(Route.direct(g[i]));

                if (variant == 2) {
                    c[i] = new Process("C" + num, cfg.maxBatchSize(), checkoutDelay, rand);
                    c[i].setExcludeDelayFromWait(true);
                    t[i].addRoute(Route.ungroup(c[i]));
                    c[i].addRoute(Route.direct(nOut[i]));
                } else {
                    u[i] = new Process("U" + num, cfg.maxBatchSize(), null, rand);
                    v[i] = new Process("V" + num, 0, null, rand);
                    v[i].setRoutingMode(Process.RoutingMode.PROBABILITY);
                    r[i] = new Process("R" + num, 0, repeatDelay, rand);
                    c[i] = new Process("C" + num, 0, checkoutDelay, rand);
                    c[i].setExcludeDelayFromWait(true);
                    zOut[i] = new Dispose("Z" + num, rand);

                    t[i].addRoute(Route.ungroup(u[i]));
                    int idx = i;
                    BooleanSupplier bV = () -> v[idx].getTotalInside() + r[idx].getTotalInside() + c[idx].getTotalInside() > 0;
                    u[i].addRoute(Route.withBlock(v[i], bV));

                    v[i].addRoute(Route.withProbability(c[i], cfg.pPaid()));
                    v[i].addRoute(Route.withProbability(r[i], cfg.pRepeat()));
                    v[i].addRoute(Route.withProbability(zOut[i], cfg.pLeave()));

                    r[i].addRoute(Route.direct(c[i]));
                    c[i].addRoute(Route.direct(nOut[i]));
                }
            }
        }

        // Налаштування предикатів блокування B_i, B_{W,i}, B_{G,i}
        if (variant == 1) {
            for (int i = 0; i < m; i++) {
                int idx = i;
                BooleanSupplier bIn = () -> t[idx].getTotalInside() + c[idx].getTotalInside() > 0;
                nodeA.addRoute(Route.group(t[i], cfg.maxBatchSize(), bIn));
            }
        } else {
            IntSupplier[] nCollect = new IntSupplier[m];
            IntSupplier[] nWork = new IntSupplier[m];
            for (int i = 0; i < m; i++) {
                int idx = i;
                nCollect[i] = () -> f[idx].getTotalInside() + w[idx].getTotalInside() + g[idx].getTotalInside();
                nWork[i] = (variant == 2)
                        ? () -> t[idx].getTotalInside() + c[idx].getTotalInside()
                        : () -> t[idx].getTotalInside() + u[idx].getTotalInside()
                        + v[idx].getTotalInside() + r[idx].getTotalInside() + c[idx].getTotalInside();
            }

            for (int i = 0; i < m; i++) {
                int idx = i;
                BooleanSupplier bIn = () -> {
                    if (nWork[idx].getAsInt() > 0) return true;
                    int own = nCollect[idx].getAsInt();
                    if (own >= cfg.maxBatchSize()) return true;
                    if (own == 0) {
                        for (int j = 0; j < m; j++) {
                            if (j != idx) {
                                int other = nCollect[j].getAsInt();
                                if (other > 0 && other < cfg.maxBatchSize()) {
                                    return true;
                                }
                            }
                        }
                    }
                    return false;
                };
                BooleanSupplier bWait = () ->
                        w[idx].getTotalInside() > 0
                                || g[idx].getTotalInside() > 0
                                || nodeA.getTotalInside() > 0;
                BooleanSupplier bGroup = () ->
                        w[idx].getTotalInside() > 0
                                || f[idx].getTotalInside() > 0
                                || (nodeA.getTotalInside() > 0 && g[idx].getTotalInside() < cfg.maxBatchSize());

                nodeA.addRoute(Route.withBlock(f[i], bIn));
                f[i].addRoute(Route.withPriority(w[i], 2, bWait));
                f[i].addRoute(Route.withPriority(g[i], 1, null));
                g[i].addRoute(Route.group(t[i], cfg.maxBatchSize(), bGroup));
            }
        }

        // Формування списку елементів у порядку пріоритету подій (C -> R -> T -> W -> миттєві -> Вхід)
        List<Element> elements = new ArrayList<>();
        for (int i = 0; i < m; i++) elements.add(c[i]);
        if (variant == 3) {
            for (int i = 0; i < m; i++) elements.add(r[i]);
        }
        for (int i = 0; i < m; i++) elements.add(t[i]);
        if (variant >= 2) {
            for (int i = 0; i < m; i++) elements.add(w[i]);
        }
        if (variant == 3) {
            for (int i = 0; i < m; i++) elements.add(u[i]);
            for (int i = 0; i < m; i++) elements.add(v[i]);
        }
        if (variant >= 2) {
            for (int i = 0; i < m; i++) elements.add(f[i]);
            for (int i = 0; i < m; i++) elements.add(g[i]);
        }
        elements.add(nodeA);
        elements.add(create);
        for (int i = 0; i < m; i++) elements.add(nOut[i]);
        if (variant == 3) {
            for (int i = 0; i < m; i++) elements.add(zOut[i]);
        }

        Model model = new Model(elements);
        model.setTraceEnabled(trace);
        model.simulate(modelingTime, warmupTime);

        return computeResult(variant, cfg, model.getObservedTime(), nodeA, t, r, c, nOut, zOut, elements);
    }

    private static Result computeResult(int variant, Config cfg, double observedTime,
                                        Process nodeA, Process[] t, Process[] r, Process[] c,
                                        Dispose[] nOut, Dispose[] zOut, List<Element> elements) {
        int m = cfg.clerksCount();
        double busySum = 0.0;
        int nPaid = 0;
        int nLeft = 0;
        int nTrips = 0;

        double servSum = 0.0, servSumSq = 0.0;
        double servMin = Double.POSITIVE_INFINITY, servMax = Double.NEGATIVE_INFINITY;
        double waitSum = 0.0, waitSumSq = 0.0;
        double waitMin = Double.POSITIVE_INFINITY, waitMax = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < m; i++) {
            busySum += t[i].getWorkTime() + c[i].getWorkTime();
            nTrips += t[i].getQuantity();

            nPaid += nOut[i].getQuantity();
            servSum += nOut[i].getServiceSum();
            servSumSq += nOut[i].getServiceSumSq();
            servMin = Math.min(servMin, nOut[i].getServiceMin());
            servMax = Math.max(servMax, nOut[i].getServiceMax());

            waitSum += nOut[i].getWaitSum();
            waitSumSq += nOut[i].getWaitSumSq();
            waitMin = Math.min(waitMin, nOut[i].getWaitMin());
            waitMax = Math.max(waitMax, nOut[i].getWaitMax());

            if (variant == 3) {
                busySum += r[i].getWorkTime();
                nTrips += r[i].getQuantity();
                nLeft += zOut[i].getQuantity();

                waitSum += zOut[i].getWaitSum();
                waitSumSq += zOut[i].getWaitSumSq();
                waitMin = Math.min(waitMin, zOut[i].getWaitMin());
                waitMax = Math.max(waitMax, zOut[i].getWaitMax());
            }
        }

        int failures = 0;
        for (Element e : elements) {
            if (e instanceof Process p) {
                failures += p.getFailure();
            }
        }

        double uClerk = busySum / (m * observedTime);
        double lMean = nodeA.getMeanQueue() / observedTime;
        double kMean = nTrips > 0 ? (double) nPaid / nTrips : 0.0;

        return new Result(
                uClerk,
                SampleStats.fromSums(nPaid, servSum, servSumSq, servMin, servMax),
                SampleStats.fromSums(nPaid + nLeft, waitSum, waitSumSq, waitMin, waitMax),
                kMean,
                lMean,
                nPaid,
                nLeft,
                nTrips,
                failures
        );
    }

    public static void main(String[] args) {
        System.out.println("=== Фрагмент протоколу подій (Модель 3, T_мод = 20 хв) ===");
        runModel(3, Config.defaults(3), 20.0, 0.0, 42L, true);

        System.out.println("\n=== Верифікація моделей (T_мод = 10000 хв, seed = 42) ===");
        System.out.println("Сценарій                           | U_клерк | T_обсл (M ± S) | T_очік (M ± S) | K_сер |  L_сер | N_опл | N_відм | N_рейс | Відм");
        System.out.println("-".repeat(132));

        double tMod = 10_000.0;
        long seed = 42L;

        // Базові прогони всіх трьох моделей
        System.out.println(runModel(1, Config.defaults(1), tMod, 0, seed, false).toTableRow("М1: Базова (t_надх=2.0)"));
        System.out.println(runModel(2, Config.defaults(2), tMod, 0, seed, false).toTableRow("М2: Очікування 1 хв (t_надх=2.0)"));
        System.out.println(runModel(3, Config.defaults(3), tMod, 0, seed, false).toTableRow("М3: Збої + 1 хв (t_надх=2.0)"));

        // Варіювання вхідних змінних
        System.out.println(runModel(1, Config.defaults(1).withMeanArrival(1.5), tMod, 0, seed, false).toTableRow("М1: t_надх = 1.5 хв (вищий потік)"));
        System.out.println(runModel(1, Config.defaults(1).withMeanArrival(4.0), tMod, 0, seed, false).toTableRow("М1: t_надх = 4.0 хв (нижчий потік)"));
        System.out.println(runModel(1, Config.defaults(1).withSearchMeanPerItem(5.0), tMod, 0, seed, false).toTableRow("М1: пошук = 5k хв (повільн. склад)"));
        System.out.println(runModel(1, Config.defaults(1).withCheckoutMean(4.0), tMod, 0, seed, false).toTableRow("М1: каса = unif(4, 1) хв"));

        // Граничні перевірки узгодженості моделей
        System.out.println(runModel(2, Config.defaults(2).withWaitTimer(0.0), tMod, 0, seed, false).toTableRow("М2: граничний t_очік_кл = 0 (== М1)"));
        System.out.println(runModel(3, Config.defaults(3).withIssues(0.0, 0.0, 0.0), tMod, 0, seed, false).toTableRow("М3: граничний p_пом=p_відс=0 (~ М2)"));
        System.out.println(runModel(3, Config.defaults(3).withIssues(0.0, 1.0, 0.0), tMod, 0, seed, false).toTableRow("М3: граничний p_відс=1, p_зам=0"));
    }
}