package io.prometheus.client;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Summary metric, to track the size of events.
 * <p>
 * Example of uses for Summaries include:
 * <ul>
 *  <li>Response latency</li>
 *  <li>Request size</li>
 * </ul>
 * 
 * <p>
 * Example Summaries:
 * <pre>
 * {@code
 *   class YourClass {
 *     static final Summary receivedBytes = Summary.build()
 *         .name("requests_size_bytes").help("Request size in bytes.").register();
 *     static final Summary requestLatency = Summary.build()
 *         .name("requests_latency_seconds").help("Request latency in seconds.").register();
 *
 *     void processRequest(Request req) {  
 *        Summary.Timer requestTimer = requestLatency.startTimer();
 *        try {
 *          // Your code here.
 *        } finally {
 *          receivedBytes.observe(req.size());
 *          requestTimer.observeDuration();
 *        }
 *     }
 *   }
 * }
 * </pre>
 * This would allow you to track request rate, average latency and average request size.
 */
public class Summary extends SimpleCollector<Summary.Child> {

  public static final String QUANTILE_LABEL = "quantile";
  public static final double[] DEFAULT_QUANTILE_VALUES = new double[] { .5, .95, .98, .99, .999 };

  private final double[] quantiles;

  Summary(Builder b) {
    super(b);
    this.quantiles = b.quantiles;
  }

  public static class Builder extends SimpleCollector.Builder<Builder, Summary> {
    private double[] quantiles = DEFAULT_QUANTILE_VALUES;

    @Override
    public Summary create() {
      if (quantiles != null && quantiles.length > 0) {
        for (double q : quantiles) {
          if (q < 0 || q > 1) {
            throw new IllegalArgumentException("quantile value must be in interval [0, 1]");
          }
        }
      }

      for (String label: labelNames) {
        if (label.equals(QUANTILE_LABEL)) {
          throw new IllegalStateException("Summary cannot have a label named '" + QUANTILE_LABEL + "'.");
        }
      }

      return new Summary(this);
    }

    /**
     * Set quantile values to be calculated.
     */
    public Builder quantiles(double... quantiles) {
      this.quantiles = quantiles;
      return this;
    }
  }

  /**
   *  Return a Builder to allow configuration of a new Summary.
   */
  public static Builder build() {
    return new Builder();
  }

  @Override
  protected Child newChild() {
    return new Child();
  }

  /**
   * Represents an event being timed.
   */
  public static class Timer {
    private final Child child;
    private final long start;
    private Timer(Child child) {
      this.child = child;
      start = Child.timeProvider.nanoTime();
    }
    /**
     * Observe the amount of time in seconds since {@link Child#startTimer} was called.
     * @return Measured duration in seconds since {@link Child#startTimer} was called.
     */
    public double observeDuration() {
      double elapsed = (Child.timeProvider.nanoTime() - start) / NANOSECONDS_PER_SECOND;
      child.observe(elapsed);
      return elapsed;
    }
  }

  /**
   * The value of a single Summary.
   * <p>
   * <em>Warning:</em> References to a Child become invalid after using
   * {@link SimpleCollector#remove} or {@link SimpleCollector#clear}.
   */
  public static class Child {
    public static class Value {
      public final double count;
      public final double sum;
      public final double[] values;

      private Value(double count, double sum, double[] values) {
        this.count = count;
        this.sum = sum;
        this.values = values;
      }

      /**
       * Return the φ-quantile estimation of the observed events.
       * The approximate value is calculated by interpolation
       * between adjacent elements in the array of observed values.
       *
       * @param φ a given quantile, in {@code [0, 1]}
       * @return the approximate value of the the φ-quantile
       */
      public double getQuantile(double φ) {
        if (φ < 0 || φ > 1) {
          throw new IllegalArgumentException("quantile value must be in interval [0, 1]");
        }

        final int length = values.length;

        if (length == 0) {
          return 0;
        }

        if (length == 1) {
          return values[0];
        }

        final double index = index(φ);

        if (index < 1) {
          return values[0];
        }

        if (index >= length) {
          return values[length - 1];
        }

        return approxQuantile(index);
      }

      /**
       * Find the index of the element of the array
       * that can be used to compute the φ-quantile value.
       *
       * @param φ a given quantile
       * @return the index for the array of values
       */
      private double index(final double φ) {
        final int length = values.length;
        return Double.compare(φ, 0) == 0 ? 0 :
               Double.compare(φ, 1) == 0 ? length :
               φ * (length + 1);
      }

      /**
       * Calculate an approximate quantile from the observed
       * value at {@code index}.
       *
       * @param index the index in the array of observed values
       * @return the approximate quantile value
       */
      private double approxQuantile(final double index) {
        final int intIdx = (int) index;

        final double lower = values[intIdx - 1];
        final double upper = values[intIdx];

        return lower + (index - Math.floor(index)) * (upper - lower);
      }

    }

    // Having these separate leaves us open to races,
    // however Prometheus as whole has other races
    // that mean adding atomicity here wouldn't be useful.
    // This should be reevaluated in the future.
    private final DoubleAdder count = new DoubleAdder();
    private final DoubleAdder sum = new DoubleAdder();

    private final UniformSampling sampling = new UniformSampling();

    static TimeProvider timeProvider = new TimeProvider();
    /**
     * Observe the given amount.
     */
    public void observe(double amt) {
      count.add(1);
      sum.add(amt);
      sampling.add(amt);
    }
    /**
     * Start a timer to track a duration.
     * <p>
     * Call {@link Timer#observeDuration} at the end of what you want to measure the duration of.
     */
    public Timer startTimer() {
      return new Timer(this);
    }
    /**
     * Get the value of the Summary.
     * <p>
     * <em>Warning:</em> The definition of {@link Value} is subject to change.
     */
    public Value get() {
      final double[] values = sampling.getValues();
      Arrays.sort(values);
      return new Value(count.sum(), sum.sum(), values);
    }
  }

  // Convenience methods.
  /**
   * Observe the given amount on the summary with no labels.
   */
  public void observe(double amt) {
    noLabelsChild.observe(amt);
  }
  /**
   * Start a timer to track a duration on the summary with no labels.
   * <p>
   * Call {@link Timer#observeDuration} at the end of what you want to measure the duration of.
   */
  public Timer startTimer() {
    return noLabelsChild.startTimer();
  }

  @Override
  public List<MetricFamilySamples> collect() {
    List<MetricFamilySamples.Sample> samples = new ArrayList<MetricFamilySamples.Sample>();
    for(Map.Entry<List<String>, Child> c: children.entrySet()) {
      Child.Value v = c.getValue().get();
      if (quantiles != null && quantiles.length > 0) {
        List<String> labelNamesWithQuantile = new ArrayList<String>(labelNames);
        labelNamesWithQuantile.add(QUANTILE_LABEL);
        for (double q : quantiles) {
          List<String> labelValuesWithQuantile = new ArrayList<String>(c.getKey());
          labelValuesWithQuantile.add(String.valueOf(q));
          samples.add(new MetricFamilySamples.Sample(fullname, labelNamesWithQuantile, labelValuesWithQuantile, v.getQuantile(q)));
        }
      }
      samples.add(new MetricFamilySamples.Sample(fullname + "_count", labelNames, c.getKey(), v.count));
      samples.add(new MetricFamilySamples.Sample(fullname + "_sum", labelNames, c.getKey(), v.sum));
    }

    MetricFamilySamples mfs = new MetricFamilySamples(fullname, Type.SUMMARY, help, samples);
    List<MetricFamilySamples> mfsList = new ArrayList<MetricFamilySamples>();
    mfsList.add(mfs);
    return mfsList;
  }

  static class TimeProvider {
    long nanoTime() {
      return System.nanoTime();
    }
  }
}
