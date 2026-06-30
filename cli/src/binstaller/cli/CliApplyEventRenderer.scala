package binstaller.cli

import binstaller.core.DownloadProgressStatus
import binstaller.core.InstallerEvent
import binstaller.core.InstallerEventObserver
import binstaller.core.InstallerRunStatus
import binstaller.core.RenderSafety

import java.io.PrintWriter
import java.net.URI

private[cli] final class CliApplyEventRenderer(
    out: PrintWriter
) extends InstallerEventObserver:
  private val width                                   = 30
  private var lastBuckets: Map[String, Int]           = Map.empty
  private var activeTools: Set[String]                = Set.empty
  private var downloadOrder: Vector[String]           = Vector.empty
  private var downloads: Map[String, DownloadRow]     = Map.empty
  private var concurrentLineMode: Boolean             = false
  private var progressBlockTools: Vector[String]      = Vector.empty
  private var progressBlockHeight: Int                = 0
  private var activeLineLength: Int                   = 0
  private var summary: Option[InstallerEvent.Summary] = None

  def onEvent(event: InstallerEvent): Unit = event match
    case progress: InstallerEvent.DownloadProgress => renderProgress(progress)
    case value: InstallerEvent.Summary             => summary = Some(value)
    case _                                         => ()

  def finish(): Unit =
    if concurrentLineMode then finishProgressBlock()
    else clearActiveLine()

  def summaryLines: Vector[String] = summary match
    case Some(value) => CliApplyOutput.summary(value)
    case None        => Vector.empty

  private def renderProgress(progress: InstallerEvent.DownloadProgress): Unit =
    updateDownloadRow(progress)
    progress.status match
      case DownloadProgressStatus.Started  => renderStarted(progress)
      case DownloadProgressStatus.Advanced => renderAdvanced(progress)
      case DownloadProgressStatus.Finished => renderFinished(progress)

  private def updateDownloadRow(progress: InstallerEvent.DownloadProgress): Unit =
    if !downloadOrder.contains(progress.toolName) then
      downloadOrder = downloadOrder :+ progress.toolName
    progress.status match
      case DownloadProgressStatus.Started  => activeTools = activeTools + progress.toolName
      case DownloadProgressStatus.Finished => activeTools = activeTools - progress.toolName
      case DownloadProgressStatus.Advanced => ()

    downloads = downloads.updated(
      progress.toolName,
      DownloadRow(
        progress.toolName,
        progress.url,
        progress.downloadedBytes,
        progress.totalBytes,
        progress.status
      )
    )

  private def renderStarted(progress: InstallerEvent.DownloadProgress): Unit =
    lastBuckets = lastBuckets.updated(progress.toolName, -1)
    if !concurrentLineMode && activeTools.size > 1 then enableConcurrentLineMode()
    else if concurrentLineMode then
      addToProgressBlock(progress.toolName)
      redrawProgressBlock()
    else renderInPlace(renderActive(progress))

  private def renderAdvanced(progress: InstallerEvent.DownloadProgress): Unit =
    val bucket = progressBucket(progress.downloadedBytes, progress.totalBytes, concurrentLineMode)
    if bucket != lastBuckets.getOrElse(progress.toolName, -1) then
      lastBuckets = lastBuckets.updated(progress.toolName, bucket)
      if concurrentLineMode then redrawProgressBlock()
      else renderInPlace(renderActive(progress))

  private def renderFinished(progress: InstallerEvent.DownloadProgress): Unit =
    lastBuckets = lastBuckets.updated(progress.toolName, 100)
    if concurrentLineMode then
      addToProgressBlock(progress.toolName)
      redrawProgressBlock()
      if activeTools.isEmpty then finishProgressBlock()
    else renderCompleted(renderCompletedLine(progress))

  private def enableConcurrentLineMode(): Unit =
    clearActiveLine()
    concurrentLineMode = true
    progressBlockTools = activeRows.map(_.toolName)
    redrawProgressBlock()

  private def addToProgressBlock(toolName: String): Unit =
    if !progressBlockTools.contains(toolName) then
      progressBlockTools = progressBlockTools :+ toolName

  private def redrawProgressBlock(): Unit =
    val rows = progressBlockRows
    if progressBlockHeight > 0 then out.print(s"\u001b[${progressBlockHeight}A")
    rows.foreach: row =>
      val line = renderRow(row)
      out.print(s"\r\u001b[2K${line.styled}\n")
    out.flush()
    progressBlockHeight = rows.size

  private def progressBlockRows: Vector[DownloadRow] = progressBlockTools.flatMap(downloads.get)

  private def finishProgressBlock(): Unit =
    out.flush()
    concurrentLineMode = false
    progressBlockTools = Vector.empty
    progressBlockHeight = 0
    activeLineLength = 0

  private def activeRows: Vector[DownloadRow] = downloadOrder.flatMap: toolName =>
    downloads.get(toolName).filter(row => activeTools.contains(row.toolName))

  private def clearActiveLine(): Unit = if activeLineLength > 0 then
    out.print(s"\r${" " * activeLineLength}\r")
    out.flush()
    activeLineLength = 0

  private def progressBucket(
      downloadedBytes: Long,
      totalBytes: Option[Long],
      lineMode: Boolean
  ): Int = totalBytes.filter(_ > 0L) match
    case Some(total) =>
      val percent = ((downloadedBytes.toDouble / total.toDouble) * 100.0).floor.toInt
      if lineMode then (percent / 10) * 10 else percent
    case None => (downloadedBytes / (1024L * 1024L)).toInt

  private def renderActive(progress: InstallerEvent.DownloadProgress): ProgressLine = renderActive(
    DownloadRow(
      progress.toolName,
      progress.url,
      progress.downloadedBytes,
      progress.totalBytes,
      progress.status
    )
  )

  private def renderRow(row: DownloadRow): ProgressLine = row.status match
    case DownloadProgressStatus.Finished => renderCompletedLine(row)
    case DownloadProgressStatus.Started | DownloadProgressStatus.Advanced => renderActive(row)

  private def renderActive(row: DownloadRow): ProgressLine =
    val label = downloadLabel(row.toolName, row.url)
    val bar   = progressBar(row.downloadedBytes, row.totalBytes)
    val bytes = byteText(row.downloadedBytes, row.totalBytes)
    // Progress text is rendered in-place only for the CLI surface; URL-derived labels are scrubbed
    // before they reach the terminal row.
    val plain  = s"⬇ downloading $label ${bar.plain} $bytes"
    val styled = s"${fansi.Color.Cyan("⬇ downloading").toString} " +
      s"${fansi.Color.Yellow(label).toString} ${bar.styled} " +
      fansi.Color.Cyan(bytes).toString
    ProgressLine(plain, styled)

  private def renderCompletedLine(progress: InstallerEvent.DownloadProgress): ProgressLine =
    renderCompletedLine(DownloadRow(
      progress.toolName,
      progress.url,
      progress.downloadedBytes,
      progress.totalBytes,
      progress.status
    ))

  private def renderCompletedLine(row: DownloadRow): ProgressLine =
    val label  = downloadLabel(row.toolName, row.url)
    val bar    = progressBar(row.downloadedBytes, row.totalBytes)
    val bytes  = byteText(row.downloadedBytes, row.totalBytes)
    val plain  = s"✅ completed $label ${bar.plain} $bytes"
    val styled = fansi.Color.Green(s"✅ completed $label").toString +
      s" ${bar.styled} ${fansi.Color.Green(bytes).toString}"
    ProgressLine(plain, styled)

  private def renderInPlace(line: ProgressLine): Unit =
    val padding = " " * (activeLineLength - line.visibleLength).max(0)
    out.print(s"\r${line.styled}$padding")
    out.flush()
    activeLineLength = line.visibleLength

  private def renderCompleted(line: ProgressLine): Unit =
    val padding = " " * (activeLineLength - line.visibleLength).max(0)
    out.print(s"\r${line.styled}$padding\n")
    out.flush()
    activeLineLength = 0

  private def progressBar(downloadedBytes: Long, totalBytes: Option[Long]): ProgressLine =
    totalBytes.filter(_ > 0L) match
      case Some(total) =>
        val ratio  = (downloadedBytes.toDouble / total.toDouble).max(0.0).min(1.0)
        val filled = (ratio * width).round.toInt
        val pct    = (ratio * 100.0).round.toInt
        val empty  = width - filled
        val plain  = s"[${"█" * filled}${"░" * empty}] $pct%"
        val styled = s"[${barColor(ratio)("█" * filled).toString}" +
          s"${fansi.Color.Blue("░" * empty).toString}] ${percentColor(pct)(s"$pct%").toString}"
        ProgressLine(plain, styled)
      case None =>
        val plain = s"[${"█" * width}]"
        ProgressLine(plain, fansi.Color.Magenta(plain).toString)

  private def barColor(ratio: Double): fansi.Attrs =
    if ratio >= 1.0 then fansi.Color.Green
    else if ratio >= 0.7 then fansi.Color.Yellow
    else fansi.Color.Cyan

  private def percentColor(percent: Int): fansi.Attrs =
    if percent >= 100 then fansi.Color.Green
    else if percent >= 70 then fansi.Color.Yellow
    else fansi.Color.Cyan

  private def byteText(downloadedBytes: Long, totalBytes: Option[Long]): String = totalBytes match
    case Some(total) => s"${formatBytes(downloadedBytes)}/${formatBytes(total)}"
    case None        => formatBytes(downloadedBytes)

  private def formatBytes(bytes: Long): String =
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    if bytes >= gib then f"${bytes / gib}%.1f GiB"
    else if bytes >= mib then f"${bytes / mib}%.1f MiB"
    else if bytes >= kib then f"${bytes / kib}%.1f KiB"
    else s"$bytes B"

  private def downloadLabel(toolName: String, url: String): String =
    val safeToolName = RenderSafety.terminalLine(toolName)
    val safeFileName = fileName(url)
    if safeFileName == safeToolName then safeToolName else s"$safeToolName $safeFileName"

  private def fileName(url: String): String =
    val fallback = "download"
    scala.util.Try(URI.create(url).getPath)
      .toOption
      .flatMap(path => Option(path).map(_.split('/').toVector.filter(_.nonEmpty).lastOption))
      .flatten
      .map(RenderSafety.terminalLine(_))
      .getOrElse(fallback)

private[cli] final case class DownloadRow(
    toolName: String,
    url: String,
    downloadedBytes: Long,
    totalBytes: Option[Long],
    status: DownloadProgressStatus
)

private[cli] final case class ProgressLine(plain: String, styled: String):
  def visibleLength: Int = plain.length

private[cli] object CliApplyOutput:

  def colorLines(lines: Vector[String]): Vector[String] = lines.map(colorLine)

  private def colorLine(line: String): String =
    if line.startsWith("installed ") then fansi.Color.Green(line).toString
    else if line.startsWith("failed ") then fansi.Color.Red(line).toString
    else line

  def summary(event: InstallerEvent.Summary): Vector[String] =
    val status =
      if event.status == InstallerRunStatus.Succeeded then
        fansi.Color.Green("🎉 apply completed successfully").toString
      else fansi.Color.Red("💥 apply finished with errors").toString
    Vector(
      "",
      fansi.Color.Magenta("✨ Summary").toString,
      s"  ${fansi.Color.Green(s"✅ installed: ${event.installed}").toString}",
      s"  ${fansi.Color.Red(s"❌ failed: ${event.failed}").toString}",
      s"  ${fansi.Color.Yellow(s"⏭ skipped: ${event.skipped}").toString}",
      s"  ${fansi.Color.Cyan(s"🚦 exit code: ${event.exitCode}").toString}",
      s"  $status"
    )
