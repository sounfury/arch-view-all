package processing.awt;

/**
 * Processing 4.4.1's animation thread calls render() with no try/catch.
 * On macOS + JDK 21+ (especially Homebrew JDK 26), FlipBufferStrategy.show()
 * throws IllegalStateException: "Buffers have not been created" while the
 * window is still realizing or being resized. That kills the animation thread.
 */
class PSurfaceAWT$9 extends processing.core.PSurfaceNone.AnimationThread {
  final PSurfaceAWT this$0;

  PSurfaceAWT$9(PSurfaceAWT surface) {
    surface.super();
    this.this$0 = surface;
  }

  public void callDraw() {
    super.callDraw();
    try {
      this$0.render();
    } catch (IllegalStateException ex) {
      String message = ex.getMessage();
      if (message == null || !message.contains("Buffers have not been created")) {
        throw ex;
      }
    }
  }
}
