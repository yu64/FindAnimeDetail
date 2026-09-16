package app.presentation.command.parse;

import app.util.IResult;

public interface ICommandSyntaxParser {
  
  public IResult<CommandExpression, String> parse(String text);
}
