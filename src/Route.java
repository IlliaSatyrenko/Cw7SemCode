import java.util.function.BooleanSupplier;

public class Route {
    private final Element target;
    private final int priority;
    private final double probability;
    private final BooleanSupplier blockCondition;
    private final boolean groupBatch;
    private final int maxBatchSize;
    private final boolean ungroupBatch;

    private Route(Element target,
                  int priority,
                  double probability,
                  BooleanSupplier blockCondition,
                  boolean groupBatch,
                  int maxBatchSize,
                  boolean ungroupBatch) {
        this.target = target;
        this.priority = priority;
        this.probability = probability;
        this.blockCondition = blockCondition;
        this.groupBatch = groupBatch;
        this.maxBatchSize = maxBatchSize;
        this.ungroupBatch = ungroupBatch;
    }

    public static Route direct(Element target) {
        return new Route(target, 1, 1.0, null, false, 1, false);
    }

    public static Route withBlock(Element target, BooleanSupplier blockCondition) {
        return new Route(target, 1, 1.0, blockCondition, false, 1, false);
    }

    public static Route withPriority(Element target, int priority, BooleanSupplier blockCondition) {
        return new Route(target, priority, 1.0, blockCondition, false, 1, false);
    }

    public static Route withProbability(Element target, double probability) {
        return new Route(target, 1, probability, null, false, 1, false);
    }

    public static Route group(Element target, int maxBatchSize, BooleanSupplier blockCondition) {
        return new Route(target, 1, 1.0, blockCondition, true, maxBatchSize, false);
    }

    public static Route ungroup(Element target) {
        return new Route(target, 1, 1.0, null, false, 1, true);
    }

    public Element getTarget() {
        return target;
    }

    public int getPriority() {
        return priority;
    }

    public double getProbability() {
        return probability;
    }

    public boolean isBlocked() {
        return blockCondition != null && blockCondition.getAsBoolean();
    }

    public boolean isGroupBatch() {
        return groupBatch;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public boolean isUngroupBatch() {
        return ungroupBatch;
    }
}