package me.ialistannen.simplecodetester.checks.defaults;

import edu.kit.informatik.Terminal;
import java.util.List;
import me.ialistannen.simplecodetester.exceptions.CheckFailedException;
import me.ialistannen.simplecodetester.submission.CompiledFile;

public class StaticInputOutputCheck extends MainClassRunnerCheck {

  private List<String> input;
  private String expectedOutput;

  public StaticInputOutputCheck(List<String> input, String expectedOutput) {
    this.input = input;
    this.expectedOutput = expectedOutput;
  }

  @Override
  protected List<String> getInput(CompiledFile file) {
    return input;
  }

  @Override
  protected void assertOutputValid(CompiledFile file) {
    String actualOutput = Terminal.getOutput(file.classLoader());

    if (!expectedOutput.equals(actualOutput)) {
      throw new CheckFailedException(
          String.format("The output of %s was\n'%s'\n, expected\n'%s'.", file.qualifiedName(),
              actualOutput, this.expectedOutput
          )
      );
    }
  }
}
