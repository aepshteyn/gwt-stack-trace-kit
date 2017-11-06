package solutions.trsoftware.gwt.stacktrace.client.util;

/**
 * Can be used to shorten some if statements that are too long.
 *
 * @author Alex
 */
public class LogicUtils {

  public static boolean bothNotNullAndEqual(Object o1, Object o2) {
    return bothNotNull(o1, o2) && o1.equals(o2);
  }

  public static boolean bothNotNullAndNotEqual(Object o1, Object o2) {
    return bothNotNull(o1, o2) && !o1.equals(o2);
  }

  public static boolean bothNotNull(Object o1, Object o2) {
    return o1 != null && o2 != null;
  }

  public static boolean bothNull(Object o1, Object o2) {
    return o1 == null && o2 == null;
  }

  /** Like {@link Object#equals(Object)}, but allows {@code null} values */
  public static boolean eq(Object o1, Object o2) {
    if (o1 != null)
      return o1.equals(o2);
    else if (o2 == null)
      return true;  // both null
    return false; // one is null
  }

  // TODO: unit test these new methods
  /**
   * Similar to a the Javascript expression {@code o1 || o2} when applied to non-boolean objects.
   * @return o1 if it's not null, otherwise o2.
   */
  public static <T> T firstNonNull(T o1, T o2) {
    if (o1 != null)
      return o1;
    return o2;
  }

  /**
   * @return the first of the given elements which is not null, or {@code null} if there isn't a non-null input element.
   */
  public static <T> T firstNonNull(T... objects) {
    for (T x : objects) {
      if (x != null)
        return x;
    }
    return null;
  }
}
