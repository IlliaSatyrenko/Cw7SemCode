import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;

public class ExperimentRunner {

    private static final int THREADS = Math.max(2, Runtime.getRuntime().availableProcessors());
    private static final long[] TRANSIENT_SEEDS = {101L, 102L, 103L, 104L, 105L};
    private static final double[] TIME_GRID = buildTimeGrid();

    private static final double TRANSIENT_REL_ERROR = 0.01;
    private static final double CHEBYSHEV_REL_ERROR = 0.03;

    // Зафіксовані за підрозділом 4.2 параметри одного експерименту (T_мод = 40 000 хв, p = 35)
    private static final double WARMUP_TIME = 10_000.0;
    private static final double OBSERVE_TIME = 30_000.0;
    private static final double MODELING_TIME = WARMUP_TIME + OBSERVE_TIME;
    private static final int RUNS_P = 35;

    // Пілотні параметри (для відтворення підрозділу 4.2 перемкнути RUN_PREP_STAGE = true)
    private static final double[] OBSERVE_CANDIDATES = {10_000.0, 20_000.0, 30_000.0, 40_000.0, 50_000.0};
    private static final int PILOT_RUNS = 30;
    private static final boolean RUN_PREP_STAGE = false;
    private static final boolean SHOW_CHARTS = true;

    // Табличні критичні значення F-критерію Фішера при α = 0.05 та p = 35:
    private static final double F_CRIT_2_102 = 3.0855; // q = 3, df1 = 2, df2 = 3*(35-1) = 102
    private static final double F_CRIT_1_68 = 3.9819;  // q = 2, df1 = 1, df2 = 2*(35-1) = 68
    private static final double F_CRIT_3_136 = 2.6713; // q = 4, df1 = 3, df2 = 4*(35-1) = 136
    private static final double F_CRIT_1_136 = 3.9107; // 2^2 ефекти, df1 = 1, df2 = 136

    private static double[] buildTimeGrid() {
        List<Double> pts = new ArrayList<>();
        for (double t = 250.0; t <= 5_000.0; t += 250.0) pts.add(t);
        for (double t = 6_000.0; t <= 30_000.0; t += 1_000.0) pts.add(t);
        return pts.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public record TransientData(
            int variant,
            double[] times,
            double[][] uClerkPct,
            double[] uMeanPct,
            double[][] tWaitMin,
            double[] tWaitMeanMin,
            double detectedWarmupU,
            double detectedWarmupT
    ) {}

    public record PilotSummary(
            double observeTime,
            int pTarget,
            String limitingTargetMetric,
            int pWithL,
            String limitingAllMetric
    ) {}

    public record BatchMetrics(
            String label,
            double[] uPct,
            double[] tWait,
            double[] tServ,
            double[] kMean,
            double[] lMean,
            double[] clientWaitStd,
            double[] clientServStd,
            double clientWaitMin,
            double clientWaitMax,
            double clientServMin,
            double clientServMax,
            double meanPaid,
            double meanLeft,
            double meanTrips,
            int totalFailures
    ) {}

    public record AnovaResult(
            int df1,
            int df2,
            double dFactor,
            double dResidual,
            double fCalc,
            double fCrit,
            boolean significant
    ) {}

    public record Factorial2x2Result(
            double effectA,
            double fA,
            double effectB,
            double fB,
            double effectAB,
            double fAB,
            double dResidual,
            double fOverall
    ) {}

    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        long tStart = System.currentTimeMillis();

        try {
            if (RUN_PREP_STAGE) {
                runPreparationStage(pool);
            }

            System.out.printf(Locale.US,
                    "=== ОСНОВНІ СЕРІЇ ЕКСПЕРИМЕНТІВ (T_перех=%.0f хв, T_стац=%.0f хв, T_мод=%.0f хв, p=%d, β=0.95) ===%n",
                    WARMUP_TIME, OBSERVE_TIME, MODELING_TIME, RUNS_P);

            // Серії 1 та 2: Моделі 1, 2, 3 (один спільний набір М1 для повної узгодженості)
            BatchMetrics m1 = runBatch("Модель 1 (базова)", 1, SimModel.Config.defaults(1), RUNS_P, 210_000L, pool);
            BatchMetrics m2 = runBatch("Модель 2 (очікування 1 хв)", 2, SimModel.Config.defaults(2), RUNS_P, 220_000L, pool);
            BatchMetrics m3 = runBatch("Модель 3 (збої + 1 хв)", 3, SimModel.Config.defaults(3), RUNS_P, 230_000L, pool);

            printSeries1Report(m1);
            printSeries2Report(m1, m2, m3);

            // Серія 3: Чутливість стратегії очікування (t_очік_кл × t_надх)
            double[] waitGrid = {0.0, 0.5, 1.0, 1.5, 2.0};
            double[] arrivalGrid = {2.0, 2.5, 3.0};
            BatchMetrics[][] series3 = runSeries3(waitGrid, arrivalGrid, pool);
            printSeries3Report(waitGrid, arrivalGrid, series3);

            // Серія 4: Повний факторний план 2^2 (p_пом × p_відс)
            BatchMetrics[] series4 = runSeries4(pool);
            printSeries4Report(series4);

            // Перевірка практичних пропозицій для Розділу 5
            BatchMetrics[] proposals = runChapter5Proposals(m3, pool);
            printProposalsReport(proposals);

            long elapsedMs = System.currentTimeMillis() - tStart;
            System.out.printf(Locale.US, "%nУсі серії завершено за %.2f с (потоків: %d).%n",
                    elapsedMs / 1000.0, THREADS);

            if (SHOW_CHARTS) {
                SwingUtilities.invokeLater(() ->
                        showResultsChartsWindow(m1, m2, m3, waitGrid, arrivalGrid, series3, series4, proposals));
            }
        } finally {
            pool.shutdown();
        }
    }

    // СЕРІЯ 1: Стаціонарні статистичні оцінки базової системи (Модель 1)
    private static void printSeries1Report(BatchMetrics m1) {
        System.out.println("\n=== СЕРІЯ 1. СТАЦІОНАРНІ ОЦІНКИ БАЗОВОЇ СИСТЕМИ (МОДЕЛЬ 1, p=35) ===");
        System.out.println("Показник                  | Середнє M | СКВ σ (міжпрог.) | ±ε_Чеб (95%) | ε_відн | Мін (прогін) | Макс (прогін)");
        System.out.println("-".repeat(114));
        printSeries1Row("U_клерк, %", m1.uPct());
        printSeries1Row("T_обсл, хв", m1.tServ());
        printSeries1Row("T_очік, хв", m1.tWait());
        printSeries1Row("K_сер, од./вихід", m1.kMean());
        printSeries1Row("L_сер, клієнтів", m1.lMean());

        System.out.println("\nІндивідуальні характеристики клієнтів за 35 стаціонарними прогонами (30 000 хв кожен):");
        System.out.printf(Locale.US, "  T_обсл: M = %.3f хв, середнє внутрішньопрогінне СКВ S = %.3f хв, min = %.3f хв, max = %.3f хв%n",
                mean(m1.tServ()), mean(m1.clientServStd()), m1.clientServMin(), m1.clientServMax());
        System.out.printf(Locale.US, "  T_очік: M = %.3f хв, середнє внутрішньопрогінне СКВ S = %.3f хв, min = %.3f хв, max = %.3f хв%n",
                mean(m1.tWait()), mean(m1.clientWaitStd()), m1.clientWaitMin(), m1.clientWaitMax());
        System.out.printf(Locale.US, "  Середня кількість за 30 000 хв: N_опл = %.1f клієнтів, N_рейс = %.1f виходів, відмов = %d%n",
                m1.meanPaid(), m1.meanTrips(), m1.totalFailures());
    }

    private static void printSeries1Row(String name, double[] arr) {
        double m = mean(arr);
        double s = stdDev(arr);
        double eps = chebyshevHalfWidth(s, arr.length);
        double rel = (m > 0.0) ? (100.0 * eps / m) : 0.0;
        System.out.printf(Locale.US, "%-25s | %9.3f | %16.3f | %12.3f | %5.2f%% | %12.3f | %13.3f%n",
                name, m, s, eps, rel, minVal(arr), maxVal(arr));
    }

    // СЕРІЯ 2: Порівняння Моделей 1, 2, 3 та однофакторний ANOVA (Фішер)
    private static void printSeries2Report(BatchMetrics m1, BatchMetrics m2, BatchMetrics m3) {
        System.out.println("\n=== СЕРІЯ 2. ПОРІВНЯННЯ МОДЕЛЕЙ 1–3 (p=35, T_стац=30 000 хв) ===");
        System.out.println("Модель                     | U_клерк, % (M±σ) | T_очік, хв (M±σ) | T_обсл, хв (M±σ) | K_сер (M±σ)   | L_сер (M±σ)   | N_опл   | N_відм | N_рейс");
        System.out.println("-".repeat(145));
        for (BatchMetrics bm : new BatchMetrics[]{m1, m2, m3}) {
            System.out.printf(Locale.US,
                    "%-26s | %6.2f ± %-5.2f   | %6.2f ± %-6.2f  | %6.2f ± %-6.2f  | %5.3f ± %-5.3f | %5.2f ± %-5.2f | %7.1f | %6.1f | %6.1f%n",
                    bm.label(),
                    mean(bm.uPct()), stdDev(bm.uPct()),
                    mean(bm.tWait()), stdDev(bm.tWait()),
                    mean(bm.tServ()), stdDev(bm.tServ()),
                    mean(bm.kMean()), stdDev(bm.kMean()),
                    mean(bm.lMean()), stdDev(bm.lMean()),
                    bm.meanPaid(), bm.meanLeft(), bm.meanTrips());
        }

        System.out.println("\nАбсолютні та відносні зміни між моделями:");
        printDeltaLine("М2 vs М1 (ефект очікування 1 хв)", m1, m2);
        printDeltaLine("М3 vs М2 (ефект збоїв комплектації)", m2, m3);
        printDeltaLine("М3 vs М1 (сумарний ефект)", m1, m3);

        System.out.printf(Locale.US, "%nОднофакторний ANOVA — усі 3 моделі (q=3, df1=2, df2=102, F_крит=%.4f, α=0.05):%n", F_CRIT_2_102);
        printAnovaHeader();
        printAnovaRow("U_клерк, %", computeAnova(new double[][]{m1.uPct(), m2.uPct(), m3.uPct()}, F_CRIT_2_102));
        printAnovaRow("T_очік, хв", computeAnova(new double[][]{m1.tWait(), m2.tWait(), m3.tWait()}, F_CRIT_2_102));
        printAnovaRow("T_обсл, хв", computeAnova(new double[][]{m1.tServ(), m2.tServ(), m3.tServ()}, F_CRIT_2_102));
        printAnovaRow("K_сер, од./вихід", computeAnova(new double[][]{m1.kMean(), m2.kMean(), m3.kMean()}, F_CRIT_2_102));
        printAnovaRow("L_сер, клієнтів", computeAnova(new double[][]{m1.lMean(), m2.lMean(), m3.lMean()}, F_CRIT_2_102));

        System.out.printf(Locale.US, "%nПопарний ANOVA — Модель 1 vs Модель 2 (q=2, df1=1, df2=68, F_крит=%.4f, α=0.05):%n", F_CRIT_1_68);
        printAnovaHeader();
        printAnovaRow("U_клерк, %", computeAnova(new double[][]{m1.uPct(), m2.uPct()}, F_CRIT_1_68));
        printAnovaRow("T_очік, хв", computeAnova(new double[][]{m1.tWait(), m2.tWait()}, F_CRIT_1_68));
        printAnovaRow("T_обсл, хв", computeAnova(new double[][]{m1.tServ(), m2.tServ()}, F_CRIT_1_68));
        printAnovaRow("K_сер, од./вихід", computeAnova(new double[][]{m1.kMean(), m2.kMean()}, F_CRIT_1_68));
        printAnovaRow("L_сер, клієнтів", computeAnova(new double[][]{m1.lMean(), m2.lMean()}, F_CRIT_1_68));

        System.out.printf(Locale.US, "%nПопарний ANOVA — Модель 2 vs Модель 3 (q=2, df1=1, df2=68, F_крит=%.4f, α=0.05):%n", F_CRIT_1_68);
        printAnovaHeader();
        printAnovaRow("U_клерк, %", computeAnova(new double[][]{m2.uPct(), m3.uPct()}, F_CRIT_1_68));
        printAnovaRow("T_очік, хв", computeAnova(new double[][]{m2.tWait(), m3.tWait()}, F_CRIT_1_68));
        printAnovaRow("T_обсл, хв", computeAnova(new double[][]{m2.tServ(), m3.tServ()}, F_CRIT_1_68));
        printAnovaRow("K_сер, од./вихід", computeAnova(new double[][]{m2.kMean(), m3.kMean()}, F_CRIT_1_68));
        printAnovaRow("L_сер, клієнтів", computeAnova(new double[][]{m2.lMean(), m3.lMean()}, F_CRIT_1_68));
    }

    private static void printDeltaLine(String title, BatchMetrics base, BatchMetrics target) {
        double du = mean(target.uPct()) - mean(base.uPct());
        double dw = mean(target.tWait()) - mean(base.tWait());
        double dwPct = 100.0 * dw / mean(base.tWait());
        double dk = mean(target.kMean()) - mean(base.kMean());
        double dkPct = 100.0 * dk / mean(base.kMean());
        System.out.printf(Locale.US,
                "  %-36s: ΔU = %+6.2f в.п. | ΔT_очік = %+7.2f хв (%+6.1f%%) | ΔK_сер = %+6.3f (%+5.1f%%)%n",
                title, du, dw, dwPct, dk, dkPct);
    }

    private static void printAnovaHeader() {
        System.out.println("Відгук               |     D_f     |     D_r     |   F_розр   |  F_крит  | Висновок");
        System.out.println("-".repeat(86));
    }

    private static void printAnovaRow(String label, AnovaResult r) {
        System.out.printf(Locale.US, "%-20s | %11.4f | %11.4f | %10.2f | %8.4f | %s%n",
                label, r.dFactor(), r.dResidual(), r.fCalc(), r.fCrit(),
                r.significant() ? "Значущий (H0 відх.)" : "Незначущий");
    }

    // СЕРІЯ 3: Чутливість стратегії очікування до t_очік_кл і t_надх
    private static BatchMetrics[][] runSeries3(double[] waitGrid, double[] arrivalGrid,
                                               ExecutorService pool) throws Exception {
        BatchMetrics[][] grid = new BatchMetrics[arrivalGrid.length][waitGrid.length];
        for (int a = 0; a < arrivalGrid.length; a++) {
            for (int w = 0; w < waitGrid.length; w++) {
                SimModel.Config cfg = SimModel.Config.defaults(2)
                        .withMeanArrival(arrivalGrid[a])
                        .withWaitTimer(waitGrid[w]);
                long seedBase = 300_000L + 10_000L * (a * waitGrid.length + w);
                String lbl = String.format(Locale.US, "t_надх=%.1f, t_очік=%.1f", arrivalGrid[a], waitGrid[w]);
                grid[a][w] = runBatch(lbl, 2, cfg, RUNS_P, seedBase, pool);
            }
        }
        return grid;
    }

    private static void printSeries3Report(double[] waitGrid, double[] arrivalGrid, BatchMetrics[][] grid) {
        System.out.println("\n=== СЕРІЯ 3. ЧУТЛИВІСТЬ СТРАТЕГІЇ ОЧІКУВАННЯ (МОДЕЛЬ 2, p=35) ===");
        System.out.println("t_надх, хв | t_очік_кл, хв | U_клерк, % (M±σ) | T_очік, хв (M±σ) | T_обсл, хв | K_сер, од./вихід | L_сер, од.");
        System.out.println("-".repeat(110));
        for (int a = 0; a < arrivalGrid.length; a++) {
            for (int w = 0; w < waitGrid.length; w++) {
                BatchMetrics bm = grid[a][w];
                System.out.printf(Locale.US,
                        "%10.1f | %13.1f | %6.2f ± %-5.2f   | %6.2f ± %-5.2f   | %10.2f | %16.3f | %10.3f%n",
                        arrivalGrid[a], waitGrid[w],
                        mean(bm.uPct()), stdDev(bm.uPct()),
                        mean(bm.tWait()), stdDev(bm.tWait()),
                        mean(bm.tServ()), mean(bm.kMean()), mean(bm.lMean()));
            }
            System.out.println("-".repeat(110));
        }
    }

    // СЕРІЯ 4: Повний факторний план 2^2 (p_пом × p_відс) для Моделі 3
    private static BatchMetrics[] runSeries4(ExecutorService pool) throws Exception {
        BatchMetrics[] cells = new BatchMetrics[4];
        // Порядок клітинок: (0,0), (1,0) [A=+1], (0,1) [B=+1], (1,1) [A=+1, B=+1]
        cells[0] = runBatch("(-1,-1) Без збоїв (0; 0)", 3,
                SimModel.Config.defaults(3).withIssues(0.0, 0.0, 0.0), RUNS_P, 410_000L, pool);
        cells[1] = runBatch("(+1,-1) Лише помилки (0.15; 0)", 3,
                SimModel.Config.defaults(3).withIssues(0.15, 0.0, 0.0), RUNS_P, 420_000L, pool);
        cells[2] = runBatch("(-1,+1) Лише дефіцит (0; 0.10)", 3,
                SimModel.Config.defaults(3).withIssues(0.0, 0.10, 0.25), RUNS_P, 430_000L, pool);
        cells[3] = runBatch("(+1,+1) Обидва збої (0.15; 0.10)", 3,
                SimModel.Config.defaults(3).withIssues(0.15, 0.10, 0.25), RUNS_P, 440_000L, pool);
        return cells;
    }

    private static void printSeries4Report(BatchMetrics[] c) {
        System.out.println("\n=== СЕРІЯ 4. ПОВНИЙ ФАКТОРНИЙ ПЛАН 2^2 (A: p_пом ∈ {0; 0.15}, B: p_відс ∈ {0; 0.10}, p=35) ===");
        System.out.println("Режим плану 2^2                   | U_клерк, % (M±σ) | T_очік, хв (M±σ) | T_обсл, хв (M±σ) | K_сер (M±σ)   | L_сер (M±σ)   | N_відм");
        System.out.println("-".repeat(138));
        for (BatchMetrics bm : c) {
            System.out.printf(Locale.US,
                    "%-33s | %6.2f ± %-5.2f   | %6.2f ± %-6.2f  | %6.2f ± %-6.2f  | %5.3f ± %-5.3f | %5.2f ± %-5.2f | %6.1f%n",
                    bm.label(),
                    mean(bm.uPct()), stdDev(bm.uPct()),
                    mean(bm.tWait()), stdDev(bm.tWait()),
                    mean(bm.tServ()), stdDev(bm.tServ()),
                    mean(bm.kMean()), stdDev(bm.kMean()),
                    mean(bm.lMean()), stdDev(bm.lMean()),
                    bm.meanLeft());
        }

        System.out.printf(Locale.US,
                "%nДисперсійний аналіз плану 2^2 (F_крит(1,136)=%.4f для A, B, AB; F_крит(3,136)=%.4f загальний, α=0.05):%n",
                F_CRIT_1_136, F_CRIT_3_136);
        System.out.println("Відгук         | Ефект A(p_пом) |    F_A    | Ефект B(p_відс) |    F_B    | Взаємодія AB |   F_AB   | F_заг(3,136)");
        System.out.println("-".repeat(115));
        printFactorialRow("U_клерк, %", computeFactorial2x2(c[0].uPct(), c[1].uPct(), c[2].uPct(), c[3].uPct()));
        printFactorialRow("T_очік, хв", computeFactorial2x2(c[0].tWait(), c[1].tWait(), c[2].tWait(), c[3].tWait()));
        printFactorialRow("T_обсл, хв", computeFactorial2x2(c[0].tServ(), c[1].tServ(), c[2].tServ(), c[3].tServ()));
        printFactorialRow("K_сер, од./вих", computeFactorial2x2(c[0].kMean(), c[1].kMean(), c[2].kMean(), c[3].kMean()));
        printFactorialRow("L_сер, од.", computeFactorial2x2(c[0].lMean(), c[1].lMean(), c[2].lMean(), c[3].lMean()));
    }

    private static Factorial2x2Result computeFactorial2x2(double[] y00, double[] y10, double[] y01, double[] y11) {
        int p = y00.length;
        double m00 = mean(y00), m10 = mean(y10), m01 = mean(y01), m11 = mean(y11);
        double effA = ((m10 + m11) - (m00 + m01)) / 2.0;
        double effB = ((m01 + m11) - (m00 + m10)) / 2.0;
        double effAB = ((m11 - m01) - (m10 - m00)) / 2.0;

        double ssA = p * effA * effA;
        double ssB = p * effB * effB;
        double ssAB = p * effAB * effAB;

        double ssRes = sumSqDev(y00, m00) + sumSqDev(y10, m10) + sumSqDev(y01, m01) + sumSqDev(y11, m11);
        double dRes = ssRes / (4.0 * (p - 1));
        double fOverall = ((ssA + ssB + ssAB) / 3.0) / dRes;

        return new Factorial2x2Result(effA, ssA / dRes, effB, ssB / dRes, effAB, ssAB / dRes, dRes, fOverall);
    }

    private static void printFactorialRow(String label, Factorial2x2Result r) {
        System.out.printf(Locale.US,
                "%-14s | %+14.3f | %9.2f | %+15.3f | %9.2f | %+12.3f | %8.2f | %12.2f%n",
                label, r.effectA(), r.fA(), r.effectB(), r.fB(), r.effectAB(), r.fAB(), r.fOverall());
    }

    // ПЕРЕВІРКА ПРАКТИЧНИХ ПРОПОЗИЦІЙ ДЛЯ РОЗДІЛУ 5 (пробні запуски, p=35)
    private static BatchMetrics[] runChapter5Proposals(BatchMetrics m3Base, ExecutorService pool) throws Exception {
        SimModel.Config base3 = SimModel.Config.defaults(3);
        BatchMetrics[] list = new BatchMetrics[6];
        list[0] = m3Base;
        // П1: Актуальний облік залишків у каталозі (дефіцит 10% -> 1%)
        list[1] = runBatch("П1: Облік залишків (p_відс=0.01)", 3,
                base3.withIssues(0.15, 0.01, 0.25), RUNS_P, 510_000L, pool);
        // П2: Штрих-код контроль комплектації на складі (помилки 15% -> 2%)
        list[2] = runBatch("П2: Контроль на складі (p_пом=0.02)", 3,
                base3.withIssues(0.02, 0.10, 0.25), RUNS_P, 520_000L, pool);
        // П3: Комплексна цифровізація (p_пом=0.02, p_відс=0.01, t_очік_кл=1 хв)
        list[3] = runBatch("П3: Комплекс (0.02; 0.01; 1 хв)", 3,
                base3.withIssues(0.02, 0.01, 0.25), RUNS_P, 530_000L, pool);
        // П4: Комплексна цифровізація без очікування (p_пом=0.02, p_відс=0.01, t_очік_кл=0 хв)
        list[4] = runBatch("П4: Комплекс без тайм. (0.02; 0.01; 0)", 3,
                base3.withIssues(0.02, 0.01, 0.25).withWaitTimer(0.0), RUNS_P, 540_000L, pool);
        // П5: Штатне масштабування (4 клерки при незмінних збоях М3)
        list[5] = runBatch("П5: 4 клерки (збої М3 без змін)", 3,
                withClerksCount(base3, 4), RUNS_P, 550_000L, pool);
        return list;
    }

    private static void printProposalsReport(BatchMetrics[] list) {
        System.out.println("\n=== ПЕРЕВІРКА ПРОПОЗИЦІЙ ЩОДО ПОЛІПШЕННЯ СИСТЕМИ (РОЗДІЛ 5, p=35, T_стац=30 000 хв) ===");
        System.out.println("Сценарій                                | U_клерк, % | T_очік, хв (Δ%)    | T_обсл, хв | K_сер  | L_сер  | N_відм");
        System.out.println("-".repeat(114));
        double baseWait = mean(list[0].tWait());
        for (BatchMetrics bm : list) {
            double w = mean(bm.tWait());
            double dwPct = 100.0 * (w - baseWait) / baseWait;
            System.out.printf(Locale.US,
                    "%-39s | %10.2f | %7.2f (%+6.1f%%)  | %10.2f | %6.3f | %6.2f | %6.1f%n",
                    bm.label(), mean(bm.uPct()), w, dwPct, mean(bm.tServ()),
                    mean(bm.kMean()), mean(bm.lMean()), bm.meanLeft());
        }
    }

    private static SimModel.Config withClerksCount(SimModel.Config c, int clerks) {
        return new SimModel.Config(
                c.meanArrival(), c.roadMean(), c.roadHalfWidth(),
                c.searchMeanPerItem(), c.searchCv(),
                c.checkoutMean(), c.checkoutHalfWidth(),
                c.waitTimer(), c.pWrongItem(), c.pOutOfStock(), c.pReplacement(),
                clerks, c.maxBatchSize()
        );
    }

    // БАГАТОПОТОКОВИЙ ЗАПУСК ОДНІЄЇ СЕРІЇ ПРОГОНІВ
    private static BatchMetrics runBatch(String label, int variant, SimModel.Config cfg,
                                         int runs, long seedBase, ExecutorService pool) throws Exception {
        List<Future<SimModel.Result>> futures = new ArrayList<>(runs);
        for (int i = 0; i < runs; i++) {
            final long seed = seedBase + i + 1;
            futures.add(pool.submit(() -> SimModel.runModel(variant, cfg, MODELING_TIME, WARMUP_TIME, seed, false)));
        }

        double[] uPct = new double[runs];
        double[] tWait = new double[runs];
        double[] tServ = new double[runs];
        double[] kMean = new double[runs];
        double[] lMean = new double[runs];
        double[] clientWaitStd = new double[runs];
        double[] clientServStd = new double[runs];
        double cWaitMin = Double.POSITIVE_INFINITY, cWaitMax = Double.NEGATIVE_INFINITY;
        double cServMin = Double.POSITIVE_INFINITY, cServMax = Double.NEGATIVE_INFINITY;
        double sumPaid = 0.0, sumLeft = 0.0, sumTrips = 0.0;
        int totalFailures = 0;

        for (int i = 0; i < runs; i++) {
            SimModel.Result r = futures.get(i).get();
            uPct[i] = r.uClerk() * 100.0;
            tWait[i] = r.tWait().mean();
            tServ[i] = r.tService().mean();
            kMean[i] = r.kMean();
            lMean[i] = r.lMean();
            clientWaitStd[i] = r.tWait().std();
            clientServStd[i] = r.tService().std();
            if (r.tWait().min() < cWaitMin) cWaitMin = r.tWait().min();
            if (r.tWait().max() > cWaitMax) cWaitMax = r.tWait().max();
            if (r.tService().min() < cServMin) cServMin = r.tService().min();
            if (r.tService().max() > cServMax) cServMax = r.tService().max();
            sumPaid += r.nPaid();
            sumLeft += r.nLeft();
            sumTrips += r.nTrips();
            totalFailures += r.failures();
        }

        return new BatchMetrics(
                label, uPct, tWait, tServ, kMean, lMean,
                clientWaitStd, clientServStd,
                cWaitMin, cWaitMax, cServMin, cServMax,
                sumPaid / runs, sumLeft / runs, sumTrips / runs, totalFailures
        );
    }

    // ПІДГОТОВЧИЙ ЕТАП (підрозділ 4.2: перехідний період та пілот Чебишева)
    private static void runPreparationStage(ExecutorService pool) throws Exception {
        System.out.printf(Locale.US,
                "=== ЕТАП 1. ПЕРЕХІДНИЙ ПЕРІОД (5 траєкторій, [250; 30000] хв, ε_перех = %.0f%%) ===%n",
                TRANSIENT_REL_ERROR * 100.0);
        for (int v = 1; v <= 3; v++) {
            TransientData td = computeTransient(v, pool);
            printTransientSpreadTable(td);
        }

        System.out.printf(Locale.US,
                "%n=== ЕТАП 2. ПІДБІР T_стац ТА p ЗА ЧЕБИШЕВИМ (p_піл=%d, T_перех=%.0f хв, ε_відн=%.0f%%, β=0.95) ===%n",
                PILOT_RUNS, WARMUP_TIME, CHEBYSHEV_REL_ERROR * 100.0);
        List<PilotSummary> summaries = new ArrayList<>();
        for (double observe : OBSERVE_CANDIDATES) {
            summaries.add(runPilotForHorizon(observe, pool));
        }
        System.out.println("\nT_стац, хв | T_мод, хв | p (цільові) | Сумарно p*T_мод | Обмежуюча цільова     | p (з L_сер)");
        System.out.println("-".repeat(98));
        for (PilotSummary s : summaries) {
            double tMod = WARMUP_TIME + s.observeTime();
            System.out.printf(Locale.US, "%10.0f | %9.0f | %11d | %15.0f | %-21s | %11d%n",
                    s.observeTime(), tMod, s.pTarget(), s.pTarget() * tMod, s.limitingTargetMetric(), s.pWithL());
        }
    }

    private static TransientData computeTransient(int variant, ExecutorService pool) throws Exception {
        SimModel.Config cfg = SimModel.Config.defaults(variant);
        int nTraj = TRANSIENT_SEEDS.length;
        int nPts = TIME_GRID.length;
        double[][] uPct = new double[nTraj][nPts];
        double[][] tWait = new double[nTraj][nPts];

        List<Future<?>> futures = new ArrayList<>();
        for (int s = 0; s < nTraj; s++) {
            final int trajIdx = s;
            final long seed = TRANSIENT_SEEDS[s];
            for (int p = 0; p < nPts; p++) {
                final int ptIdx = p;
                final double tMod = TIME_GRID[p];
                futures.add(pool.submit(() -> {
                    SimModel.Result res = SimModel.runModel(variant, cfg, tMod, 0.0, seed, false);
                    uPct[trajIdx][ptIdx] = Math.min(100.0, res.uClerk() * 100.0);
                    tWait[trajIdx][ptIdx] = res.tWait().mean();
                }));
            }
        }
        for (Future<?> f : futures) f.get();

        double[] uMean = columnMeans(uPct);
        double[] tWaitMean = columnMeans(tWait);
        return new TransientData(variant, TIME_GRID, uPct, uMean, tWait, tWaitMean,
                detectTransientTimeRel(TIME_GRID, uPct, uMean, TRANSIENT_REL_ERROR),
                detectTransientTimeRel(TIME_GRID, tWait, tWaitMean, TRANSIENT_REL_ERROR));
    }

    private static double detectTransientTimeRel(double[] times, double[][] series, double[] means, double relEps) {
        int n = times.length;
        if (maxSpread(extractColumn(series, n - 1)) / means[n - 1] > relEps) return -1.0;
        int idx = n - 1;
        while (idx > 0 && maxSpread(extractColumn(series, idx - 1)) / means[idx - 1] <= relEps) idx--;
        return times[idx];
    }

    private static void printTransientSpreadTable(TransientData data) {
        System.out.printf("%n--- Модель %d ---%n", data.variant());
        double[] printMarks = {500, 1000, 2000, 3000, 5000, 8000, 10000, 15000, 20000, 25000, 30000};
        for (double mark : printMarks) {
            int idx = findIndex(data.times(), mark);
            if (idx < 0) continue;
            double uMean = data.uMeanPct()[idx];
            double uSpread = maxSpread(extractColumn(data.uClerkPct(), idx));
            double wMean = data.tWaitMeanMin()[idx];
            double wSpread = maxSpread(extractColumn(data.tWaitMin(), idx));
            System.out.printf(Locale.US, "%7.0f | U=%6.2f%% (Δ=%.2f%%; %.2f%%) | T_очік=%6.2f (Δ=%.2f)%n",
                    mark, uMean, uSpread, 100.0 * uSpread / uMean, wMean, wSpread);
        }
    }

    private static PilotSummary runPilotForHorizon(double observe, ExecutorService pool) throws Exception {
        double tMod = WARMUP_TIME + observe;
        int maxTargetP = 1, maxAllP = 1;
        String limTarget = "", limAll = "";
        for (int variant = 1; variant <= 3; variant++) {
            SimModel.Config cfg = SimModel.Config.defaults(variant);
            List<Future<SimModel.Result>> futures = new ArrayList<>(PILOT_RUNS);
            for (int i = 0; i < PILOT_RUNS; i++) {
                final int v = variant;
                final long seed = 10_000L * v + i + 1;
                futures.add(pool.submit(() -> SimModel.runModel(v, cfg, tMod, WARMUP_TIME, seed, false)));
            }
            double[] uArr = new double[PILOT_RUNS], tWaitArr = new double[PILOT_RUNS];
            double[] tServArr = new double[PILOT_RUNS], kArr = new double[PILOT_RUNS], lArr = new double[PILOT_RUNS];
            for (int i = 0; i < PILOT_RUNS; i++) {
                SimModel.Result r = futures.get(i).get();
                uArr[i] = r.uClerk() * 100.0;
                tWaitArr[i] = r.tWait().mean();
                tServArr[i] = r.tService().mean();
                kArr[i] = r.kMean();
                lArr[i] = r.lMean();
            }
            String[] names = {"U_клерк", "T_очік", "T_обсл", "K_сер", "L_сер"};
            double[][] data = {uArr, tWaitArr, tServArr, kArr, lArr};
            for (int mIdx = 0; mIdx < names.length; mIdx++) {
                boolean nonStat = (variant == 3 && (mIdx == 1 || mIdx == 2 || mIdx == 4));
                if (nonStat) continue;
                int pReq = chebyshevRequiredP(data[mIdx], CHEBYSHEV_REL_ERROR);
                boolean isTarget = (mIdx < 4);
                if (isTarget && pReq > maxTargetP) {
                    maxTargetP = pReq;
                    limTarget = "М" + variant + ": " + names[mIdx];
                }
                if (pReq > maxAllP) {
                    maxAllP = pReq;
                    limAll = "М" + variant + ": " + names[mIdx];
                }
            }
        }
        return new PilotSummary(observe, maxTargetP, limTarget, maxAllP, limAll);
    }

    // СТАТИСТИЧНІ ФУНКЦІЇ ТА ANOVA
    public static AnovaResult computeAnova(double[][] groups, double fCrit) {
        int q = groups.length;
        int p = groups[0].length;
        double[] groupMeans = new double[q];
        double grandMean = 0.0;

        for (int j = 0; j < q; j++) {
            groupMeans[j] = mean(groups[j]);
            grandMean += groupMeans[j];
        }
        grandMean /= q;

        double ssFactor = 0.0;
        double ssResidual = 0.0;
        for (int j = 0; j < q; j++) {
            double diff = groupMeans[j] - grandMean;
            ssFactor += p * diff * diff;
            ssResidual += sumSqDev(groups[j], groupMeans[j]);
        }

        int df1 = q - 1;
        int df2 = q * (p - 1);
        double dFactor = ssFactor / df1;
        double dResidual = ssResidual / df2;
        double fCalc = (dResidual > 0.0) ? (dFactor / dResidual) : Double.POSITIVE_INFINITY;
        return new AnovaResult(df1, df2, dFactor, dResidual, fCalc, fCrit, fCalc > fCrit);
    }

    private static int chebyshevRequiredP(double[] values, double relEps) {
        double m = mean(values);
        double s = stdDev(values);
        double eps = m * relEps;
        return Math.max(1, (int) Math.ceil(20.0 * Math.pow(s / eps, 2)));
    }

    private static double chebyshevHalfWidth(double stdDev, int p) {
        return stdDev * Math.sqrt(20.0 / p);
    }

    private static double sumSqDev(double[] arr, double m) {
        double sum = 0.0;
        for (double v : arr) {
            double d = v - m;
            sum += d * d;
        }
        return sum;
    }

    private static int findIndex(double[] arr, double target) {
        for (int i = 0; i < arr.length; i++) {
            if (Math.abs(arr[i] - target) < 1e-6) return i;
        }
        return -1;
    }

    private static double[] extractColumn(double[][] matrix, int col) {
        double[] res = new double[matrix.length];
        for (int i = 0; i < matrix.length; i++) res[i] = matrix[i][col];
        return res;
    }

    private static double[] columnMeans(double[][] matrix) {
        int cols = matrix[0].length;
        double[] res = new double[cols];
        for (int c = 0; c < cols; c++) res[c] = mean(extractColumn(matrix, c));
        return res;
    }

    private static double mean(double[] arr) {
        double sum = 0.0;
        for (double v : arr) sum += v;
        return sum / arr.length;
    }

    private static double stdDev(double[] arr) {
        if (arr.length < 2) return 0.0;
        return Math.sqrt(sumSqDev(arr, mean(arr)) / (arr.length - 1));
    }

    private static double minVal(double[] arr) {
        double min = Double.POSITIVE_INFINITY;
        for (double v : arr) if (v < min) min = v;
        return min;
    }

    private static double maxVal(double[] arr) {
        double max = Double.NEGATIVE_INFINITY;
        for (double v : arr) if (v > max) max = v;
        return max;
    }

    private static double maxSpread(double[] arr) {
        return maxVal(arr) - minVal(arr);
    }

    // ВІЗУАЛІЗАЦІЯ ГРАФІКІВ СЕРІЙ 2–4 ТА РОЗДІЛУ 5 У JFRAME З ЕКСПОРТОМ У PNG
    private static void showResultsChartsWindow(
            BatchMetrics m1, BatchMetrics m2, BatchMetrics m3,
            double[] waitGrid, double[] arrivalGrid, BatchMetrics[][] series3,
            BatchMetrics[] series4, BatchMetrics[] proposals) {

        JFrame frame = new JFrame("Курсова робота (А12) — Результати Серій 2–4 та пропозицій Розділу 5");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1140, 700);
        frame.setLocationRelativeTo(null);

        JTabbedPane tabs = new JTabbedPane();
        List<JPanel> panels = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();

        // --- Серія 2: Порівняння Моделей 1–3 ---
        String[] s2Labels = {"Модель 1 (базова)", "Модель 2 (очікування 1 хв)", "Модель 3 (збої + 1 хв)"};
        addTab(tabs, panels, fileNames, "С2: U_клерк", "series2_U_clerk.png", new BarChartPanel(
                "Серія 2: середнє завантаження клерків U_клерк (p=35, T_стац=30 000 хв)",
                "Варіант моделі", "Завантаження клерків U_клерк, %",
                s2Labels,
                new double[]{mean(m1.uPct()), mean(m2.uPct()), mean(m3.uPct())},
                new double[]{chebyshevHalfWidth(stdDev(m1.uPct()), RUNS_P),
                        chebyshevHalfWidth(stdDev(m2.uPct()), RUNS_P),
                        chebyshevHalfWidth(stdDev(m3.uPct()), RUNS_P)},
                false, "%.2f%%"
        ));
        addTab(tabs, panels, fileNames, "С2: T_очік", "series2_T_wait.png", new BarChartPanel(
                "Серія 2: середній час очікування клієнта T_очік (p=35, T_стац=30 000 хв)",
                "Варіант моделі", "Середній час очікування T_очік, хв",
                s2Labels,
                new double[]{mean(m1.tWait()), mean(m2.tWait()), mean(m3.tWait())},
                null, true, "%.2f хв"
        ));
        addTab(tabs, panels, fileNames, "С2: K_сер", "series2_K_mean.png", new BarChartPanel(
                "Серія 2: середня кількість вимог за один вихід на склад K_сер (p=35)",
                "Варіант моделі", "Середня кількість вимог K_сер, од./вихід",
                s2Labels,
                new double[]{mean(m1.kMean()), mean(m2.kMean()), mean(m3.kMean())},
                new double[]{chebyshevHalfWidth(stdDev(m1.kMean()), RUNS_P),
                        chebyshevHalfWidth(stdDev(m2.kMean()), RUNS_P),
                        chebyshevHalfWidth(stdDev(m3.kMean()), RUNS_P)},
                true, "%.3f"
        ));

        // --- Серія 3: Чутливість стратегії очікування ---
        String[] s3CurveLabels = {"t_надх = 2,0 хв (високе навантаження)",
                "t_надх = 2,5 хв (середнє навантаження)",
                "t_надх = 3,0 хв (помірне навантаження)"};
        double[][] s3Wait = new double[arrivalGrid.length][waitGrid.length];
        double[][] s3U = new double[arrivalGrid.length][waitGrid.length];
        double[][] s3K = new double[arrivalGrid.length][waitGrid.length];
        for (int a = 0; a < arrivalGrid.length; a++) {
            for (int w = 0; w < waitGrid.length; w++) {
                s3Wait[a][w] = mean(series3[a][w].tWait());
                s3U[a][w] = mean(series3[a][w].uPct());
                s3K[a][w] = mean(series3[a][w].kMean());
            }
        }
        addTab(tabs, panels, fileNames, "С3: T_очік", "series3_T_wait.png", new LineChartPanel(
                "Серія 3: залежність часу очікування T_очік від таймера добору t_очік_кл (Модель 2)",
                "Тривалість очікування клерка t_очік_кл, хв", "Середній час очікування T_очік, хв",
                waitGrid, s3Wait, s3CurveLabels, true
        ));
        addTab(tabs, panels, fileNames, "С3: U_клерк", "series3_U_clerk.png", new LineChartPanel(
                "Серія 3: залежність завантаження клерків U_клерк від таймера добору t_очік_кл (Модель 2)",
                "Тривалість очікування клерка t_очік_кл, хв", "Завантаження клерків U_клерк, %",
                waitGrid, s3U, s3CurveLabels, true
        ));
        addTab(tabs, panels, fileNames, "С3: K_сер", "series3_K_mean.png", new LineChartPanel(
                "Серія 3: залежність розміру партії K_сер від таймера добору t_очік_кл (Модель 2)",
                "Тривалість очікування клерка t_очік_кл, хв", "Середня кількість вимог K_сер, од./вихід",
                waitGrid, s3K, s3CurveLabels, true
        ));

        // --- Серія 4: План 2^2 ---
        String[] s4Labels = {"Без збоїв (0; 0)", "Лише помилки (0,15; 0)",
                "Лише дефіцит (0; 0,10)", "Обидва збої (0,15; 0,10)"};
        addTab(tabs, panels, fileNames, "С4: T_очік (2^2)", "series4_T_wait.png", new BarChartPanel(
                "Серія 4 (план 2^2): декомпозиція впливу помилок і дефіциту на T_очік (p=35)",
                "Комбінація факторів (p_пом; p_відс)", "Середній час очікування T_очік, хв",
                s4Labels,
                new double[]{mean(series4[0].tWait()), mean(series4[1].tWait()),
                        mean(series4[2].tWait()), mean(series4[3].tWait())},
                null, true, "%.2f хв"
        ));
        addTab(tabs, panels, fileNames, "С4: U_клерк (2^2)", "series4_U_clerk.png", new BarChartPanel(
                "Серія 4 (план 2^2): декомпозиція впливу помилок і дефіциту на U_клерк (p=35)",
                "Комбінація факторів (p_пом; p_відс)", "Завантаження клерків U_клерк, %",
                s4Labels,
                new double[]{mean(series4[0].uPct()), mean(series4[1].uPct()),
                        mean(series4[2].uPct()), mean(series4[3].uPct())},
                null, false, "%.2f%%"
        ));

        // --- Розділ 5: Пропозиції щодо поліпшення системи ---
        String[] pLabels = {"М3 (поточна)", "П1: Облік", "П2: Контроль",
                "П3: Комплекс (1 хв)", "П4: Комплекс (0 хв)", "П5: 4 клерки"};
        double[] pWait = new double[proposals.length];
        double[] pU = new double[proposals.length];
        for (int i = 0; i < proposals.length; i++) {
            pWait[i] = mean(proposals[i].tWait());
            pU[i] = mean(proposals[i].uPct());
        }
        addTab(tabs, panels, fileNames, "Р5: Пропозиції T_очік", "ch5_proposals_T_wait.png", new BarChartPanel(
                "Оцінка ефективності пропозицій щодо поліпшення системи: час очікування T_очік",
                "Сценарій удосконалення", "Середній час очікування T_очік, хв",
                pLabels, pWait, null, true, "%.2f хв"
        ));
        addTab(tabs, panels, fileNames, "Р5: Пропозиції U_клерк", "ch5_proposals_U_clerk.png", new BarChartPanel(
                "Оцінка ефективності пропозицій щодо поліпшення системи: завантаження U_клерк",
                "Сценарій удосконалення", "Завантаження клерків U_клерк, %",
                pLabels, pU, null, false, "%.2f%%"
        ));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        JButton saveOneBtn = new JButton("Зберегти активний графік (PNG)");
        JButton saveAllBtn = new JButton("Зберегти всі графіки у папку charts/");
        JLabel statusLbl = new JLabel(" ");

        saveOneBtn.addActionListener(e -> {
            int idx = tabs.getSelectedIndex();
            try {
                File out = savePanelAsPng(panels.get(idx), fileNames.get(idx));
                statusLbl.setText("Збережено: " + out.getPath());
            } catch (IOException ex) {
                statusLbl.setText("Помилка збереження: " + ex.getMessage());
            }
        });

        saveAllBtn.addActionListener(e -> {
            try {
                for (int i = 0; i < panels.size(); i++) {
                    savePanelAsPng(panels.get(i), fileNames.get(i));
                }
                statusLbl.setText("Усі " + panels.size() + " графіків збережено у папку charts/");
            } catch (IOException ex) {
                statusLbl.setText("Помилка збереження: " + ex.getMessage());
            }
        });

        toolbar.add(saveOneBtn);
        toolbar.add(saveAllBtn);
        toolbar.add(statusLbl);

        frame.setLayout(new BorderLayout());
        frame.add(toolbar, BorderLayout.NORTH);
        frame.add(tabs, BorderLayout.CENTER);
        frame.setVisible(true);
    }

    private static void addTab(JTabbedPane tabs, List<JPanel> panels, List<String> files,
                               String tabTitle, String fileName, JPanel panel) {
        tabs.addTab(tabTitle, panel);
        panels.add(panel);
        files.add(fileName);
    }

    private static File savePanelAsPng(JPanel panel, String fileName) throws IOException {
        File dir = new File("charts");
        if (!dir.exists()) dir.mkdirs();
        int w = Math.max(panel.getWidth(), 1080);
        int h = Math.max(panel.getHeight(), 620);
        panel.setSize(w, h);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        panel.paint(g2);
        g2.dispose();
        File out = new File(dir, fileName);
        ImageIO.write(img, "png", out);
        return out;
    }

    // Панель лінійних графіків (Серія 3)
    private static class LineChartPanel extends JPanel {
        private static final Color[] PALETTE = {
                new Color(31, 78, 121),
                new Color(192, 57, 43),
                new Color(39, 130, 74),
                new Color(211, 120, 20),
                new Color(125, 60, 152)
        };

        private final String title, xLabel, yLabel;
        private final double[] xValues;
        private final double[][] series;
        private final String[] seriesLabels;
        private final boolean drawPoints;

        LineChartPanel(String title, String xLabel, String yLabel,
                       double[] xValues, double[][] series, String[] seriesLabels, boolean drawPoints) {
            this.title = title;
            this.xLabel = xLabel;
            this.yLabel = yLabel;
            this.xValues = xValues;
            this.series = series;
            this.seriesLabels = seriesLabels;
            this.drawPoints = drawPoints;
            setBackground(Color.WHITE);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int width = getWidth(), height = getHeight();
            int padLeft = 85, padRight = 35, padTop = 78, padBottom = 65;
            int plotW = width - padLeft - padRight, plotH = height - padTop - padBottom;
            if (plotW <= 40 || plotH <= 40) {
                g2.dispose();
                return;
            }

            double xMin = xValues[0], xMax = xValues[xValues.length - 1];
            double yMin = Double.POSITIVE_INFINITY, yMax = Double.NEGATIVE_INFINITY;
            for (double[] s : series) {
                for (double v : s) {
                    if (v < yMin) yMin = v;
                    if (v > yMax) yMax = v;
                }
            }
            double yRange = Math.max(yMax - yMin, 1e-6);
            yMin = Math.max(0.0, yMin - 0.12 * yRange);
            yMax = yMax + 0.12 * yRange;
            if (yLabel.contains("%")) yMax = Math.min(100.0, yMax);

            g2.setColor(Color.BLACK);
            g2.setFont(new Font("Times New Roman", Font.BOLD, 16));
            FontMetrics fmTitle = g2.getFontMetrics();
            g2.drawString(title, (width - fmTitle.stringWidth(title)) / 2, 26);

            g2.setFont(new Font("Times New Roman", Font.PLAIN, 13));
            FontMetrics fmLeg = g2.getFontMetrics();
            int legendY = 50, totalLegW = 0;
            for (String lbl : seriesLabels) totalLegW += 24 + fmLeg.stringWidth(lbl) + 18;
            int curX = Math.max(padLeft, (width - totalLegW) / 2);
            for (int i = 0; i < seriesLabels.length; i++) {
                g2.setColor(PALETTE[i % PALETTE.length]);
                g2.setStroke(new BasicStroke(2.4f));
                g2.drawLine(curX, legendY - 4, curX + 18, legendY - 4);
                g2.setColor(Color.DARK_GRAY);
                g2.drawString(seriesLabels[i], curX + 22, legendY);
                curX += 24 + fmLeg.stringWidth(seriesLabels[i]) + 18;
            }

            g2.setFont(new Font("Times New Roman", Font.PLAIN, 12));
            FontMetrics fmAxis = g2.getFontMetrics();
            int yTicks = 6;
            for (int i = 0; i <= yTicks; i++) {
                double val = yMin + (yMax - yMin) * i / yTicks;
                int py = padTop + plotH - (int) Math.round((double) plotH * i / yTicks);
                g2.setColor(new Color(225, 225, 225));
                g2.setStroke(new BasicStroke(1f));
                g2.drawLine(padLeft, py, padLeft + plotW, py);
                g2.setColor(Color.BLACK);
                String txt = String.format(Locale.US, "%.1f", val);
                g2.drawString(txt, padLeft - fmAxis.stringWidth(txt) - 8, py + 4);
            }

            for (double xv : xValues) {
                int px = padLeft + (int) Math.round((xv - xMin) / (xMax - xMin) * plotW);
                g2.setColor(new Color(225, 225, 225));
                g2.setStroke(new BasicStroke(1f));
                g2.drawLine(px, padTop, px, padTop + plotH);
                g2.setColor(Color.BLACK);
                String txt = (xMax <= 10.0) ? String.format(Locale.US, "%.1f", xv) : String.format(Locale.US, "%.0f", xv);
                g2.drawString(txt, px - fmAxis.stringWidth(txt) / 2, padTop + plotH + 18);
            }

            g2.setColor(Color.BLACK);
            g2.setStroke(new BasicStroke(1.4f));
            g2.drawRect(padLeft, padTop, plotW, plotH);

            for (int s = 0; s < series.length; s++) {
                g2.setColor(PALETTE[s % PALETTE.length]);
                g2.setStroke(new BasicStroke(2.4f));
                for (int i = 1; i < xValues.length; i++) {
                    double x1 = padLeft + (xValues[i - 1] - xMin) / (xMax - xMin) * plotW;
                    double y1 = padTop + plotH - (series[s][i - 1] - yMin) / (yMax - yMin) * plotH;
                    double x2 = padLeft + (xValues[i] - xMin) / (xMax - xMin) * plotW;
                    double y2 = padTop + plotH - (series[s][i] - yMin) / (yMax - yMin) * plotH;
                    g2.draw(new Line2D.Double(x1, y1, x2, y2));
                }
                if (drawPoints) {
                    for (int i = 0; i < xValues.length; i++) {
                        double x = padLeft + (xValues[i] - xMin) / (xMax - xMin) * plotW;
                        double y = padTop + plotH - (series[s][i] - yMin) / (yMax - yMin) * plotH;
                        g2.fill(new Ellipse2D.Double(x - 4, y - 4, 8, 8));
                    }
                }
            }

            g2.setColor(Color.BLACK);
            g2.setFont(new Font("Times New Roman", Font.BOLD, 14));
            FontMetrics fmLbl = g2.getFontMetrics();
            g2.drawString(xLabel, padLeft + (plotW - fmLbl.stringWidth(xLabel)) / 2, height - 18);

            Graphics2D gY = (Graphics2D) g2.create();
            gY.rotate(-Math.PI / 2.0);
            gY.drawString(yLabel, -(padTop + (plotH + fmLbl.stringWidth(yLabel)) / 2), 24);
            gY.dispose();
            g2.dispose();
        }
    }

    // Панель стовпчикових діаграм (Серія 2, Серія 4, Розділ 5)
    private static class BarChartPanel extends JPanel {
        private static final Color[] BAR_COLORS = {
                new Color(47, 85, 151),
                new Color(68, 114, 196),
                new Color(192, 80, 77),
                new Color(84, 130, 53),
                new Color(112, 173, 71),
                new Color(128, 100, 162)
        };

        private final String title, xLabel, yLabel, valueFormat;
        private final String[] labels;
        private final double[] values;
        private final double[] errors;
        private final boolean startAtZero;

        BarChartPanel(String title, String xLabel, String yLabel,
                      String[] labels, double[] values, double[] errors,
                      boolean startAtZero, String valueFormat) {
            this.title = title;
            this.xLabel = xLabel;
            this.yLabel = yLabel;
            this.labels = labels;
            this.values = values;
            this.errors = errors;
            this.startAtZero = startAtZero;
            this.valueFormat = valueFormat;
            setBackground(Color.WHITE);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int width = getWidth(), height = getHeight();
            int padLeft = 88, padRight = 35, padTop = 62, padBottom = 78;
            int plotW = width - padLeft - padRight, plotH = height - padTop - padBottom;
            if (plotW <= 40 || plotH <= 40) {
                g2.dispose();
                return;
            }

            double minV = Double.POSITIVE_INFINITY, maxV = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < values.length; i++) {
                double err = (errors != null) ? errors[i] : 0.0;
                if (values[i] - err < minV) minV = values[i] - err;
                if (values[i] + err > maxV) maxV = values[i] + err;
            }
            double yMin = startAtZero ? 0.0 : Math.max(0.0, Math.floor((minV - 5.0) / 5.0) * 5.0);
            double yMax = maxV + 0.16 * Math.max(maxV - yMin, 1.0);
            if (yLabel.contains("%")) yMax = Math.min(102.0, Math.max(yMax, 100.5));

            g2.setColor(Color.BLACK);
            g2.setFont(new Font("Times New Roman", Font.BOLD, 16));
            FontMetrics fmTitle = g2.getFontMetrics();
            g2.drawString(title, (width - fmTitle.stringWidth(title)) / 2, 28);

            g2.setFont(new Font("Times New Roman", Font.PLAIN, 12));
            FontMetrics fmAxis = g2.getFontMetrics();
            int yTicks = 6;
            for (int i = 0; i <= yTicks; i++) {
                double val = yMin + (yMax - yMin) * i / yTicks;
                int py = padTop + plotH - (int) Math.round((double) plotH * i / yTicks);
                g2.setColor(new Color(225, 225, 225));
                g2.setStroke(new BasicStroke(1f));
                g2.drawLine(padLeft, py, padLeft + plotW, py);
                g2.setColor(Color.BLACK);
                String txt = String.format(Locale.US, "%.1f", val);
                g2.drawString(txt, padLeft - fmAxis.stringWidth(txt) - 8, py + 4);
            }

            g2.setColor(Color.BLACK);
            g2.setStroke(new BasicStroke(1.4f));
            g2.drawRect(padLeft, padTop, plotW, plotH);

            int n = values.length;
            double slotW = (double) plotW / n;
            int barW = (int) Math.min(110, slotW * 0.56);

            for (int i = 0; i < n; i++) {
                int cx = padLeft + (int) Math.round(slotW * (i + 0.5));
                int topY = padTop + plotH - (int) Math.round((values[i] - yMin) / (yMax - yMin) * plotH);
                int barH = (padTop + plotH) - topY;

                g2.setColor(BAR_COLORS[i % BAR_COLORS.length]);
                g2.fillRect(cx - barW / 2, topY, barW, Math.max(1, barH));
                g2.setColor(Color.DARK_GRAY);
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRect(cx - barW / 2, topY, barW, Math.max(1, barH));

                int labelY = topY - 8;
                if (errors != null && errors[i] > 0.0) {
                    int errTop = padTop + plotH - (int) Math.round((values[i] + errors[i] - yMin) / (yMax - yMin) * plotH);
                    int errBot = padTop + plotH - (int) Math.round((values[i] - errors[i] - yMin) / (yMax - yMin) * plotH);
                    g2.setColor(Color.BLACK);
                    g2.setStroke(new BasicStroke(1.6f));
                    g2.drawLine(cx, errTop, cx, errBot);
                    g2.drawLine(cx - 7, errTop, cx + 7, errTop);
                    g2.drawLine(cx - 7, errBot, cx + 7, errBot);
                    labelY = errTop - 7;
                }

                g2.setColor(Color.BLACK);
                g2.setFont(new Font("Times New Roman", Font.BOLD, 13));
                FontMetrics fmVal = g2.getFontMetrics();
                String valTxt = String.format(Locale.US, valueFormat, values[i]);
                g2.drawString(valTxt, cx - fmVal.stringWidth(valTxt) / 2, Math.max(padTop + 14, labelY));

                g2.setFont(new Font("Times New Roman", Font.PLAIN, 12));
                FontMetrics fmX = g2.getFontMetrics();
                g2.drawString(labels[i], cx - fmX.stringWidth(labels[i]) / 2, padTop + plotH + 20);
            }

            g2.setColor(Color.BLACK);
            g2.setFont(new Font("Times New Roman", Font.BOLD, 14));
            FontMetrics fmLbl = g2.getFontMetrics();
            g2.drawString(xLabel, padLeft + (plotW - fmLbl.stringWidth(xLabel)) / 2, height - 18);

            Graphics2D gY = (Graphics2D) g2.create();
            gY.rotate(-Math.PI / 2.0);
            gY.drawString(yLabel, -(padTop + (plotH + fmLbl.stringWidth(yLabel)) / 2), 24);
            gY.dispose();
            g2.dispose();
        }
    }
}