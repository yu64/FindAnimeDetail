package app.usecase.command;

import java.util.List;

public sealed interface ICommand {


  public static record HelpCommand(String cmd) implements ICommand {};


  public static record FindCommand(Format fmt, List<String> words) implements ICommand {

    public static enum Format {
      TSV,
      MARKDOWN
    }
  };


}