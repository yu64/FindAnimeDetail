package app.presentation.command.def;

import app.presentation.command.parse.CommandExpression;
import app.usecase.IInput;
import app.util.IResult;

public interface ICommandDef {
  

  public String name();
  public String help();
  public IResult<IInput, String> map(CommandExpression cmd);
}
