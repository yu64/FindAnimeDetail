package app.presentation.command.def;

import app.presentation.command.parse.CommandElementReader;
import app.presentation.command.parse.CommandExpression;
import app.usecase.IInput;
import app.usecase.IInput.HelpInput;
import app.util.IResult;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped 
public class HelpCommandDef implements ICommandDef {
  
  
  @Override
  public String name() {
    return "help";
  }

  @Override
  public String help() {
    return """
      [/help]
      コマンドの使い方を表示します。

      すべて表示:
      /help

      特定のコマンドだけ表示:
      /help #command find
      /help find
      """;
  }
  

  @Override 
  public IResult<IInput, String> map(CommandExpression cmd)
  {
    CommandElementReader reader = new CommandElementReader(cmd);
    
    var cmdName = reader.readStr("command")
      .map(v -> v.orElse(""));
      
    return cmdName.map(v -> new HelpInput(v));
  }

}
