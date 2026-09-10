package app.util;

import java.util.List;
import java.util.Optional;


public class CollectionUtil {
  
  private CollectionUtil() {}

  
  public static <T> Optional<T> get(List<T> c, int index)
  {
    if(0 <= index && index < c.size()) return Optional.of(c.get(index));
    return Optional.empty();
  }

}
