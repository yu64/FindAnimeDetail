package app.usecase.help;

import app.usecase.IInput.HelpInput;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped 
public class HelpUsecase {
  
  private IHelpProvider help;

  @Inject 
  public HelpUsecase(IHelpProvider help)
  {
    this.help = help;
  }

  public String run(HelpInput input)
  {
    return this.help.getHelp(input.cmd());
  }
}
