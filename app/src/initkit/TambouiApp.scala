package initkit

import dev.tamboui.layout.Flex.{END, SPACE_BETWEEN}
import dev.tamboui.toolkit.Toolkit.{panel, row, spacer, text}
import dev.tamboui.toolkit.app.ToolkitRunner

object TambouiApp {
  def run(name: String, title: String): Unit = {
    val runner = ToolkitRunner.create()
    try {
      runner.run { () =>
        panel(
          title,
          text(s"Hello, $name").bold().cyan(),
          text(s"Workspace: ${os.pwd}").dim(),
          spacer(),
          row(text("Press q to exit").dim()).length(1).flex(END)
        ).rounded().flex(SPACE_BETWEEN)
      }
    } finally {
      runner.close()
    }
  }
}
