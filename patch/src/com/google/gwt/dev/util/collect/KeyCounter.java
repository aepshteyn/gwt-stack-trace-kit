package com.google.gwt.dev.util.collect;

/**
 * Counts occurrences of items, up to Integer.MAX_VALUE. This data structure
 * is useful in tandem with a map to count occurrences of keys and then sort
 * those keys by frequency.  Example:
 * <pre>
 * Map&lt;T, KeyCounter&lt;T&gt;&gt; counterMap;
 * // init counterMap (use LinkedHashMap for stable sorting).
 * ...
 * // count the items
 * for (T x : someList) {
 *   counterMap.get(x).increment();
 * }
 * // get the sorted values
 * List&lt;KeyCounter&lt;T&gt;&gt; sorted = new ArrayList&lt;KeyCounter&lt;T&gt;&gt;(counterMap.values);
 * Collections.sort(sorted);
 * </pre>
 */
public class KeyCounter<T> implements Comparable<KeyCounter<T>> {
  private T key;
  private int count;

  public KeyCounter(T key) {
    this.key = key;
  }

  public void increment() {
    if (count < Integer.MAX_VALUE)  // avoid overflow
      count++;
  }

  public T getKey() {
    return key;
  }

  public int getCount() {
    return count;
  }

  public int compareTo(KeyCounter o) {
    return o.count - count;  // descending sort order by frequency
  }
}
