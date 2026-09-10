package app.presentation.mapper;

import app.usecase.command.ICommand;
import app.usecase.command.ICommand.FindCommand;
import app.usecase.command.ICommand.HelpCommand;
import app.usecase.command.ICommand.FindCommand.Format;
import app.util.IResult;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CommandMapper {
  
  public IResult<ICommand, String> map(ParsedCommand cmd)
  {
    return switch(cmd.command()) {
      case "help" -> this.toHelp(cmd);
      case "find" -> this.toFind(cmd);
      default -> IResult.err("不明なコマンドです: " + cmd.command());
    };
  }

  private IResult<ICommand, String> toHelp(ParsedCommand cmd)
  {
    CommandElementReader reader = new CommandElementReader(cmd);
    
    var cmdName = reader.readStr("command")
      .map(v -> v.orElse(""));
      
    return cmdName.map(v -> new HelpCommand(v));
  }
  

  private IResult<ICommand, String> toFind(ParsedCommand cmd)
  {
    CommandElementReader reader = new CommandElementReader(cmd);

    // 各引数の読み取り・型チェック
    var fmtResult = reader.readEnum("format", Format.class);
    if(fmtResult instanceof IResult.Err) return fmtResult.err();

    var wordResult = reader.readStrList("word");
    if(wordResult instanceof IResult.Err) return wordResult.err();
    
    // 各引数のデフォルトと必須確認
    var fmt = fmtResult.ok().val()
      .orElse(Format.TSV);
    
    var word = wordResult.ok().val();
    if(word.isEmpty()) return IResult.err("word は必須です");

    // コマンド作成
    return IResult.ok(new FindCommand(fmt, word.get()));
  }
}
