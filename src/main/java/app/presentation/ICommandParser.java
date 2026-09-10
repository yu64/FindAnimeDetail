package app.presentation;

import app.presentation.mapper.ParsedCommand;
import app.util.IResult;

public interface ICommandParser {
  
  public IResult<ParsedCommand, String> parse(String text);
}
