package com.carrotsearch.ant.tasks.junit4;

import java.io.*;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.*;

import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.notification.Failure;

/**
 * A slave process running the actual tests on the target JVM.
 */
public class SlaveMain {
  /** Runtime exception. */
  static final int ERR_EXCEPTION = 255;

  /** No JUnit on classpath. */
  static final int ERR_NO_JUNIT = 254;

  /**
   * All class names to be executed as tests.
   */
  private final List<String> classes = new ArrayList<String>();

  /**
   * Listeners subscribed to tests execution.
   */
  private final List<IExecutionListener> listeners = new ArrayList<IExecutionListener>();

  /** Stored original system output. */
  private static PrintStream stdout;

  /** Stored original system error. */
  private static PrintStream stderr;

  /**
   * Base for redirected streams. 
   */
  private static class ChunkedStream extends OutputStream {
    public void write(int b) throws IOException {
      throw new IOException("Only write(byte[],int,int) calls expected from super stream.");
    }
  }

  /**
   * Execute tests.
   */
  private void execute() {
    IExecutionListener multiplexer = 
        listenerProxy(Multiplexer.forInterface(IExecutionListener.class, listeners));

    final JUnitCore core = new JUnitCore();
    core.addListener(new StreamFlusher());
    core.addListener(new RunListenerAdapter(multiplexer));
    core.run(instantiate(multiplexer, classes));
  }

  /**
   * Redirect standard streams so that the output can be passed to listeners.
   */
  private static void redirectStreams(final IExecutionListener listener) {
    stdout = System.out;
    stderr = System.err;
    System.setOut(new PrintStream(new BufferedOutputStream(new ChunkedStream() {
      @Override
      public synchronized void write(byte[] b, int off, int len) throws IOException {
        byte [] chunk = new byte [len];
        System.arraycopy(b, off, chunk, 0, len);
        listener.appendOut(chunk);
      }
    })));
    
    System.setOut(new PrintStream(new BufferedOutputStream(new ChunkedStream() {
      @Override
      public synchronized void write(byte[] b, int off, int len) throws IOException {
        byte [] chunk = new byte [len];
        System.arraycopy(b, off, chunk, 0, len);
        listener.appendErr(chunk);
      }
    })));
  }

  private static void restoreStreams() {
    System.out.flush();
    System.err.flush();
    System.setOut(stdout);
    System.setErr(stderr);
  }

  /**
   * Instantiate test classes (or try to).
   */
  private Class<?>[] instantiate(IExecutionListener multiplexer, Collection<String> classnames) {
    final List<Class<?>> instantiated = new ArrayList<Class<?>>();
    for (String className : classnames) {
      try {
        instantiated.add(Class.forName(className));
      } catch (Throwable t) {
        warn("Could not instantiate: " + className);
        try {
          multiplexer.testFailure(new Failure(
              Description.createSuiteDescription(className), t));
        } catch (Exception e) {
          warn("Could not report failure: ", t);
        }
      }
    }
    return instantiated.toArray(new Class<?>[instantiated.size()]);
  }

  /**
   * Add classes to be executed as tests.
   */
  public void addTestClasses(String... classnames) {
    this.classes.addAll(Arrays.asList(classnames));
  }

  /**
   * Run listeners to hook to the execution process.
   */
  public void addListeners(IExecutionListener... runListeners) {
    this.listeners.addAll(Arrays.asList(runListeners));
  }

  /**
   * Creates a proxy for {@link IExecutionListener} to a given handler.
   */
  static IExecutionListener listenerProxy(InvocationHandler handler) {
    return (IExecutionListener) Proxy.newProxyInstance(
        SlaveMain.class.getClassLoader(), new Class<?>[] {IExecutionListener.class},
        handler);
  }

  /**
   * Warning emitter. 
   */
  private static void warn(String string, Throwable t) {
    stderr.println("WARN: " + string);
    if (t != null) {
      stderr.println("      " + t.toString());
      t.printStackTrace(stderr);
    }
    stderr.flush();
  }

  /**
   * Warning emitter.
   */
  private static void warn(String string) {
    warn(string, null);
  }

  /**
   * Parse command line arguments.
   */
  private static void parseArguments(SlaveMain main, String[] args) throws IOException {
    for (int i = 0; i < args.length; i++) {
      if (args[i].startsWith("@")) {
        // Arguments file, one line per option.
        parseArguments(main, readArgsFile(args[i].substring(1)));
      } else {
        // The default expectation is a test class.
        main.addTestClasses(args[i]);
      }
    }
  }

  /**
   * Read arguments from a file. Newline delimited, UTF-8 encoded. No fanciness to 
   * avoid dependencies.
   */
  private static String[] readArgsFile(String argsFile) throws IOException {
    final ArrayList<String> lines = new ArrayList<String>();
    final BufferedReader reader = new BufferedReader(
        new InputStreamReader(
            new FileInputStream(argsFile), "UTF-8"));
    try {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (!line.isEmpty() && !line.startsWith("#")) {
          lines.add(line);
        }
      }
    } finally {
      reader.close();
    }
    return lines.toArray(new String [lines.size()]);
  }

  /**
   * Console entry point.
   */
  public static void main(String[] args) {
    EventWriter eventWriter = new EventWriter(System.out);
    IExecutionListener listener = listenerProxy(eventWriter);

    redirectStreams(listener);
    int exitStatus = 0;
    try {
      SlaveMain main = new SlaveMain();
      parseArguments(main, args);
      main.addListeners(listener);
      main.execute();
    } catch (Throwable t) {
      warn("Exception at main loop level?", t);
      exitStatus = -1;
    } finally {
      restoreStreams();
    }

    try { eventWriter.close(); } catch (Throwable e) {/* ignore */}
    System.exit(exitStatus);
  }
}