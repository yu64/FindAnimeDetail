package app.presentation.command.def;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import app.presentation.command.parse.CommandExpression;
import app.usecase.IInput;
import app.usecase.help.IHelpProvider;
import app.util.IResult;
import io.quarkus.arc.All;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped 
public class CommandDefRegistry implements IHelpProvider{
  
  private Map<String, ICommandDef> defMap = new HashMap<>();

  @Inject
  public CommandDefRegistry(
    @All List<ICommandDef> defs
  )
  {
    defs.forEach(d -> defMap.put(d.name(), d));
  }

  public IResult<IInput, String> map(CommandExpression expr)
  {
    return this.get(expr.command())
      .map(d -> d.map(expr))
      .orElseGet(() -> IResult.err("不明なコマンドです: " + expr.command()));
  }

  public Optional<ICommandDef> get(String cmdname)
  {
    return Optional.ofNullable(this.defMap.get(cmdname));
  }

  @Override
  public String getHelp(String command) {

    // コマンド名が指定の場合、単体の結果を返す。
    if(command != null && !command.isBlank())
    {
      var s = command.trim();
      return this.get(s)
        .map(d -> d.help())
        .orElse("不明なコマンドです: " + s);
    }

    // コマンド名が未指定の場合、すべての結果を返す。
    StringBuilder sb = new StringBuilder();
    for(ICommandDef def : this.defMap.values())
    {
      if(sb.length() != 0) sb.append("\n");
      sb.append(def.help());
    }

    return sb.toString();
  }
}
