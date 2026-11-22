import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class Job {
    private final long id;
    private final double timeIn;
    private double excludedWaitTime = 0.0;
    private final List<Job> subJobs;

    public Job(long id, double timeIn) {
        this.id = id;
        this.timeIn = timeIn;
        this.subJobs = null;
    }

    private Job(List<Job> subJobs) {
        this.id = -1;
        this.timeIn = subJobs.get(0).timeIn;
        this.subJobs = new ArrayList<>(subJobs);
    }

    public static Job createBatch(List<Job> jobs) {
        return new Job(jobs);
    }

    public long getId() {
        return id;
    }

    public double getTimeIn() {
        return timeIn;
    }

    public double getExcludedWaitTime() {
        return excludedWaitTime;
    }

    public void addExcludedWaitTime(double delta) {
        this.excludedWaitTime += delta;
    }

    public boolean isBatch() {
        return subJobs != null;
    }

    public int getBatchSize() {
        return subJobs == null ? 1 : subJobs.size();
    }

    // Розгрупування 1/k_i з відновленням первинного порядку FIFO за часом входу
    public List<Job> unpackSortedFifo() {
        if (subJobs == null) {
            return List.of(this);
        }
        List<Job> sorted = new ArrayList<>(subJobs);
        sorted.sort(Comparator.comparingDouble(Job::getTimeIn).thenComparingLong(Job::getId));
        return sorted;
    }

    @Override
    public String toString() {
        if (subJobs == null) {
            return "Job#" + id;
        }
        String ids = subJobs.stream()
                .map(j -> "#" + j.id)
                .collect(Collectors.joining(", "));
        return "Batch(k=" + subJobs.size() + ", [" + ids + "])";
    }
}