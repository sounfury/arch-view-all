// 工具补丁：在窗口初始化或调整大小时，容忍尚未就绪的绘图缓冲区，避免动画线程退出。

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
