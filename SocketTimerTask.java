import java.util.TimerTask;
import java.util.Timer;

public class SocketTimerTask extends TimerTask {

  private StudentSocketImpl sock;

  /**
   * register timer event for TCP statck
   * @param tcpTtimer TImer object to use
   * @param delay length of time before timer in milliseconds
   * @param sock socket implementation to call sock.handleTimer(ref)
   * @param ref generic object of information to pass back
   */
  public SocketTimerTask(StudentSocketImpl sock) {
    this.sock = sock;
  }

  public void run() {
    sock.handleTimer(null);
  }
}
