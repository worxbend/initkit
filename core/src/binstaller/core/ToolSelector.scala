package binstaller.core

private[core] object ToolSelector:

  def select(
      plan: ResolvedPlan,
      selection: ToolSelection
  ): Either[ResolvePlanError.SelectionFailed, ResolvedPlan] =
    val toolNames = plan.tools.map(_.name).toSet
    val unknown   = (selection.only ++ selection.skip)
      .distinct
      .filterNot(toolNames.contains)
      .map(name => s"unknown tool '$name'")

    if unknown.nonEmpty then Left(ResolvePlanError.SelectionFailed(unknown))
    else Right(plan.copy(tools = selectedTools(plan.tools, selection)))

  private def selectedTools(
      tools: Vector[ResolvedTool],
      selection: ToolSelection
  ): Vector[ResolvedTool] =
    val onlyNames = selection.only.toSet
    val skipNames = selection.skip.toSet
    val included  =
      if onlyNames.isEmpty then tools
      else tools.filter(tool => onlyNames(tool.name))

    included.filterNot(tool => skipNames(tool.name))
