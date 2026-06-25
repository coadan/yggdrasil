package ygg;

import clojure.lang.RT;

public final class NativeMain {
  private NativeMain() {}

  public static void main(String[] args) {
    NativeNamespaces.ensureLoaded();
    ygg.server$_main.invokeStatic(RT.seq(args));
  }
}
