package app.presentation;

import app.usecase.dto.ParsedCommand;

public interface ICommandParser {
  
  public ParsedCommand parse(String text);
}
