package esw.agent.app

import caseapp.{CommandName, HelpMessage, ExtraName => Short}

sealed trait AgentCliCommand

object AgentCliCommand {
  @CommandName("start")
  final case class StartCommand(
      @HelpMessage("prefix of machine. tcs.primary_machine, ocs.machine1 etc")
      @Short("p")
      prefix: String
  ) extends AgentCliCommand
}
