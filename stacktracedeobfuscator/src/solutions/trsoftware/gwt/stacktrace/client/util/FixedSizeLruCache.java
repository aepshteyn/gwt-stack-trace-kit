package solutions.trsoftware.gwt.stacktrace.client.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Implements an LRU cache which starts evicting the oldest-put entries
 * after the size limit (in number of entries) is exceeded.
 *
 * This class decorates LinkedHashMap with fixed-size logic.
 *
 * This class must be synchronized externally.  There is no way to avoid
 * locking, because an LRU cache cannot be built using the java.util.concurrent
 * classes. (Trust me, I spent a lot of time trying).
 * 
 * @author Alex
 */
public class FixedSizeLruCache<K, V> extends LinkedHashMap<K,V> {
  private int sizeLimit;

  public FixedSizeLruCache(int initialCapacity, float loadFactor, int sizeLimit) {
    super(initialCapacity, loadFactor, true);  // the third arg (true) makes the LinkedHashMap enforce the LRU property
    this.sizeLimit = sizeLimit;
  }

  public FixedSizeLruCache(int sizeLimit) {
    this(16, .75f, sizeLimit);
  }

  /** No-arg constructor to support {@link java.io.Serializable} */
  private FixedSizeLruCache() {
  }

  @Override
  protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
    return size() > sizeLimit;
  }

  public int getSizeLimit() {
    return sizeLimit;
  }

  public void setSizeLimit(int sizeLimit) {
    this.sizeLimit = sizeLimit;
  }
}