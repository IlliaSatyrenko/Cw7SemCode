import java.util.Locale;

public class Create extends Element {
    private final double meanDelay;
    private Element nextElement;
    private long nextJobId = 0;

    public Create(String name, double meanDelay, FunRand rand) {
        super(name, rand);
        this.meanDelay = meanDelay;
        this.tNext = rand.exp(meanDelay);
    }

    public void setNextElement(Element nextElement) {
        this.nextElement = nextElement;
    }

    @Override
    public void outAct() {
        super.outAct();
        Job job = new Job(++nextJobId, tCurr);
        tNext = tCurr + rand.exp(meanDelay);
        log(String.format(Locale.US, "Надходження %s (наступне о t=%.3f)", job, tNext));
        if (nextElement != null) {
            nextElement.inAct(job);
        }
    }
}