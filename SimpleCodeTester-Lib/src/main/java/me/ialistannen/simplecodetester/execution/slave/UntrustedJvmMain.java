package me.ialistannen.simplecodetester.execution.slave;

import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;

import com.google.gson.Gson;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import me.ialistannen.simplecodetester.checks.Check;
import me.ialistannen.simplecodetester.checks.CheckRunner;
import me.ialistannen.simplecodetester.checks.CheckType;
import me.ialistannen.simplecodetester.checks.SubmissionCheckResult;
import me.ialistannen.simplecodetester.checks.defaults.ImportCheck;
import me.ialistannen.simplecodetester.checks.defaults.StaticInputOutputCheck;
import me.ialistannen.simplecodetester.compilation.Compiler;
import me.ialistannen.simplecodetester.compilation.java8.memory.Java8InMemoryCompiler;
import me.ialistannen.simplecodetester.exceptions.CompilationException;
import me.ialistannen.simplecodetester.execution.MessageClient;
import me.ialistannen.simplecodetester.jvmcommunication.protocol.masterbound.CompilationFailed;
import me.ialistannen.simplecodetester.jvmcommunication.protocol.masterbound.DyingMessage;
import me.ialistannen.simplecodetester.jvmcommunication.protocol.masterbound.SlaveDiedWithUnknownError;
import me.ialistannen.simplecodetester.jvmcommunication.protocol.masterbound.SlaveStarted;
import me.ialistannen.simplecodetester.jvmcommunication.protocol.masterbound.SlaveTimedOut;
import me.ialistannen.simplecodetester.jvmcommunication.protocol.masterbound.SubmissionResult;
import me.ialistannen.simplecodetester.jvmcommunication.protocol.slavebound.CompileAndCheckSubmission;
import me.ialistannen.simplecodetester.submission.CompiledSubmission;
import me.ialistannen.simplecodetester.submission.Submission;
import me.ialistannen.simplecodetester.util.ConfiguredGson;
import me.ialistannen.simplecodetester.util.Pair;
import me.ialistannen.simplecodetester.util.Stacktrace;

/**
 * The main class of the untrusted slave vm.
 */
public class UntrustedJvmMain {

  private int port;
  private String uid;

  private MessageClient client;
  private TimerTask idleKiller;
  private CheckCompiler checkCompiler;
  private Gson gson;

  private UntrustedJvmMain(int port, String uid) throws IOException {
    this.port = port;
    this.uid = uid;

    this.gson = ConfiguredGson.createGson();
    this.client = createMessageClient();
    this.checkCompiler = new CheckCompiler();
  }

  private MessageClient createMessageClient() throws IOException {
    return new MessageClient(
        new Socket(InetAddress.getLocalHost(), port),
        gson,
        (client, message) -> {
          if (message instanceof CompileAndCheckSubmission) {
            idleKiller.cancel();

            CompileAndCheckSubmission checkSubmissionMessage = (CompileAndCheckSubmission) message;
            Submission submission = checkSubmissionMessage.getSubmission();
            receivedSubmission(submission, checkSubmissionMessage.getChecks());
          }
        }
    );
  }

  private void execute() {
    new Thread(client).start();

    client.queueMessage(new SlaveStarted(uid, ProcessHandle.current().pid()));

    // Kill yourself if you get no task in a reasonable timeframe
    idleKiller = new TimerTask() {
      @Override
      public void run() {
        client.queueMessage(new SlaveTimedOut(uid));
        shutdown();
      }
    };

    new Timer(true)
        .schedule(idleKiller, TimeUnit.SECONDS.toMillis(30));
  }

  private void receivedSubmission(Submission submission, List<Pair<CheckType, String>> checks) {
    try {
      CompiledSubmission compiledSubmission = compile(submission);

      if (!compiledSubmission.compilationOutput().successful()) {
        shutdown();
        return;
      }

      runChecks(compiledSubmission, checks);
    } catch (CompilationException e) {
      e.printStackTrace();
      client.queueMessage(new CompilationFailed(e.getOutput(), uid));
    } catch (Throwable e) {
      e.printStackTrace();
      client.queueMessage(new SlaveDiedWithUnknownError(uid, Stacktrace.getStacktrace(e)));
    } finally {
      shutdown();
    }
  }

  private CompiledSubmission compile(Submission submission) {
    Compiler compiler = new Java8InMemoryCompiler();
    CompiledSubmission compiledSubmission = compiler.compileSubmission(submission);

    if (!compiledSubmission.compilationOutput().successful()) {
      throw new CompilationException(compiledSubmission.compilationOutput());
    }

    return compiledSubmission;
  }

  private void shutdown() {
    client.queueMessage(new DyingMessage(uid));
    client.stop();
  }

  private void runChecks(CompiledSubmission compiledSubmission,
      List<Pair<CheckType, String>> receivedChecks) {

    List<Check> checks = receivedChecks.stream()
        .filter(pair -> pair.getKey() == CheckType.IMPORT || pair.getKey() == CheckType.IO)
        .map(pair -> {
          if (pair.getKey() == CheckType.IMPORT) {
            return gson.fromJson(pair.getValue(), ImportCheck.class);
          } else if (pair.getKey() == CheckType.IO) {
            return gson.fromJson(pair.getValue(), StaticInputOutputCheck.class);
          } else {
            throw new IllegalArgumentException("Unknown check");
          }
        })
        .collect(toCollection(ArrayList::new));

    List<String> checkSourceToCompile = receivedChecks.stream()
        .filter(pair -> pair.getKey() == CheckType.SOURCE_CODE)
        .map(Pair::getValue)
        .collect(toList());

    if (!checkSourceToCompile.isEmpty()) {
      List<Check> compiledChecks = checkCompiler
          .compileAndInstantiateChecks(checkSourceToCompile, new Java8InMemoryCompiler());

      checks.addAll(compiledChecks);
    }

    CheckRunner checkRunner = new CheckRunner(checks);
    SubmissionCheckResult checkResult = checkRunner.checkSubmission(compiledSubmission);

    client.queueMessage(new SubmissionResult(checkResult, uid));
  }

  public static void main(String[] args) throws IOException {
    if (args.length < 2) {
      throw new IllegalArgumentException(
          "Usage: java <program> <master port> <slave uid>"
      );
    }
    int port = Integer.parseInt(args[0]);
    String uid = args[1];

    PrintStream out = new PrintStream(new FileOutputStream("/tmp/test_out.txt"));
    System.setOut(out);
    System.setErr(out);

    System.setSecurityManager(new SubmissionSecurityManager());

    new UntrustedJvmMain(port, uid).execute();
  }
}
